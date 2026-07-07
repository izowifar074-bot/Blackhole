package com.github.izowifar.blackhole.client.render;

import com.github.izowifar.blackhole.BlackholeMod;
import com.github.izowifar.blackhole.entity.BlackHoleEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import org.joml.Quaternionf;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Draws the black hole in five layers, back to front:
 *
 * <ol>
 *   <li>The event horizon: a pitch-black depth-writing sphere.</li>
 *   <li>A camera-facing photon ring hugging the horizon, white-hot at the rim
 *       and falling off through orange.</li>
 *   <li>A wide, faint camera-facing halo suggesting lensed background light.</li>
 *   <li>A world-space accretion disk with a Keplerian (differential) swirl,
 *       relativistic doppler beaming on the approaching side, and animated
 *       turbulence clumps. The disk precesses slowly and its tilt is unique
 *       per hole.</li>
 *   <li>A radial birth flash during the first moments, and a shake plus
 *       brightness surge during the final collapse.</li>
 * </ol>
 *
 * All glow layers use {@link RenderType#lightning()} (position-color, additive
 * blending), so they bloom naturally over each other without textures.
 */
public class BlackHoleRenderer extends EntityRenderer<BlackHoleEntity, BlackHoleRenderState> {
    private static final ResourceLocation WHITE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(BlackholeMod.MOD_ID, "textures/misc/white.png");
    private static final int FULL_BRIGHT = 0xF000F0;
    private static final float TAU = (float) (Math.PI * 2.0);

    private static final int SPHERE_LAT = 20;
    private static final int SPHERE_LON = 32;
    /** Unit sphere quads, both windings so culling can never hide it. */
    private static final float[] SPHERE = buildSphere();

    private static final float[][] DISK_STOPS = {
            {0.00f, 1.00f, 0.98f, 0.92f, 1.00f},
            {0.22f, 1.00f, 0.74f, 0.36f, 0.88f},
            {0.50f, 1.00f, 0.45f, 0.12f, 0.55f},
            {0.78f, 0.62f, 0.16f, 0.05f, 0.25f},
            {1.00f, 0.28f, 0.06f, 0.02f, 0.00f},
    };

    public BlackHoleRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public BlackHoleRenderState createRenderState() {
        return new BlackHoleRenderState();
    }

    @Override
    public void extractRenderState(BlackHoleEntity entity, BlackHoleRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.ageTicks = entity.getAgeTicks() + partialTick;
        state.radius = BlackHoleEntity.radiusAt(state.ageTicks);
        long seed = entity.getUUID().getLeastSignificantBits();
        state.tiltDegrees = 12.0f + Math.floorMod(seed, 14L);
        state.precessionOffset = Math.floorMod(seed >> 8, 628L) / 100.0f;
    }

    @Override
    public boolean shouldRender(BlackHoleEntity entity, Frustum frustum, double x, double y, double z) {
        // The visuals extend far beyond the 1x1 hitbox; frustum-culling by the
        // hitbox would pop the disk out at the screen edges.
        return true;
    }

    @Override
    public void submit(BlackHoleRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
            CameraRenderState cameraState) {
        super.submit(state, poseStack, collector, cameraState);
        float t = state.ageTicks;
        float radius = state.radius;
        if (radius < 0.01f) {
            return;
        }
        float collapse = collapseProgress(t);

        poseStack.pushPose();
        if (collapse > 0.0f) {
            float shake = 0.08f * collapse;
            poseStack.translate(
                    shake * Mth.sin(t * 7.3f),
                    shake * Mth.sin(t * 8.9f + 1.7f),
                    shake * Mth.sin(t * 6.1f + 3.4f));
        }

        // 1. Event horizon
        float pulse = 1.0f + 0.05f * collapse * Mth.sin(t * 5.0f);
        float sphereRadius = radius * 0.98f * pulse;
        collector.submitCustomGeometry(poseStack, RenderType.entitySolid(WHITE_TEXTURE),
                (pose, consumer) -> emitSphere(pose, consumer, sphereRadius));

        // 2 + 3 + 5. Camera-facing glow: photon ring, halo, birth flash
        Quaternionf cameraRotation = Minecraft.getInstance().gameRenderer.getMainCamera().rotation();
        poseStack.pushPose();
        poseStack.mulPose(cameraRotation);
        float ringBoost = 1.0f + 1.6f * collapse;
        collector.submitCustomGeometry(poseStack, RenderType.lightning(), (pose, consumer) -> {
            emitPhotonRing(pose, consumer, radius, ringBoost);
            emitBirthFlash(pose, consumer, t);
        });
        poseStack.popPose();

        // 4. Accretion disk
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotation(state.precessionOffset + t * 0.004f));
        poseStack.mulPose(Axis.XP.rotationDegrees(state.tiltDegrees));
        float diskBoost = 1.0f + 0.8f * collapse;
        collector.submitCustomGeometry(poseStack, RenderType.lightning(),
                (pose, consumer) -> emitAccretionDisk(pose, consumer, radius, t, diskBoost));
        poseStack.popPose();

        poseStack.popPose();
    }

    private static float collapseProgress(float t) {
        float start = BlackHoleEntity.GROW_TICKS + BlackHoleEntity.SUSTAIN_TICKS;
        if (t <= start) {
            return 0.0f;
        }
        return Math.min(1.0f, (t - start) / BlackHoleEntity.COLLAPSE_TICKS);
    }

    private static float[] buildSphere() {
        float[] data = new float[SPHERE_LAT * SPHERE_LON * 2 * 12];
        int k = 0;
        for (int i = 0; i < SPHERE_LAT; i++) {
            float t0 = (float) Math.PI * i / SPHERE_LAT;
            float t1 = (float) Math.PI * (i + 1) / SPHERE_LAT;
            for (int j = 0; j < SPHERE_LON; j++) {
                float p0 = TAU * j / SPHERE_LON;
                float p1 = TAU * (j + 1) / SPHERE_LON;
                float[][] v = {
                        spherePoint(t0, p0), spherePoint(t1, p0),
                        spherePoint(t1, p1), spherePoint(t0, p1),
                };
                for (int n = 0; n < 4; n++) {
                    data[k++] = v[n][0];
                    data[k++] = v[n][1];
                    data[k++] = v[n][2];
                }
                for (int n = 3; n >= 0; n--) {
                    data[k++] = v[n][0];
                    data[k++] = v[n][1];
                    data[k++] = v[n][2];
                }
            }
        }
        return data;
    }

    private static float[] spherePoint(float theta, float phi) {
        return new float[] {
                Mth.sin(theta) * Mth.cos(phi),
                Mth.cos(theta),
                Mth.sin(theta) * Mth.sin(phi),
        };
    }

    private static void emitSphere(PoseStack.Pose pose, VertexConsumer consumer, float radius) {
        for (int i = 0; i < SPHERE.length; i += 3) {
            float x = SPHERE[i];
            float y = SPHERE[i + 1];
            float z = SPHERE[i + 2];
            consumer.addVertex(pose, x * radius, y * radius, z * radius)
                    .setColor(0, 0, 0, 255)
                    .setUv(0.5f, 0.5f)
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(FULL_BRIGHT)
                    .setNormal(pose, x, y, z);
        }
    }

    private static void emitPhotonRing(PoseStack.Pose pose, VertexConsumer consumer, float r, float boost) {
        // Blinding rim right at the horizon
        emitRing(pose, consumer, r * 1.00f, r * 1.07f, 64,
                1.00f, 0.97f, 0.90f, 0.95f * boost,
                1.00f, 0.85f, 0.55f, 0.85f * boost);
        // Hot falloff
        emitRing(pose, consumer, r * 1.07f, r * 1.45f, 64,
                1.00f, 0.75f, 0.35f, 0.70f * boost,
                1.00f, 0.35f, 0.08f, 0.0f);
        // Wide faint halo of lensed light
        emitRing(pose, consumer, r * 1.45f, r * 2.40f, 64,
                0.95f, 0.30f, 0.08f, 0.16f * boost,
                0.60f, 0.10f, 0.03f, 0.0f);
    }

    private static void emitRing(PoseStack.Pose pose, VertexConsumer consumer,
            float rIn, float rOut, int segments,
            float ri, float gi, float bi, float ai,
            float ro, float go, float bo, float ao) {
        float aIn = Math.min(ai, 1.0f);
        float aOut = Math.min(ao, 1.0f);
        for (int s = 0; s < segments; s++) {
            float a0 = TAU * s / segments;
            float a1 = TAU * (s + 1) / segments;
            float c0 = Mth.cos(a0);
            float s0 = Mth.sin(a0);
            float c1 = Mth.cos(a1);
            float s1 = Mth.sin(a1);
            vertex(pose, consumer, c0 * rIn, s0 * rIn, ri, gi, bi, aIn);
            vertex(pose, consumer, c1 * rIn, s1 * rIn, ri, gi, bi, aIn);
            vertex(pose, consumer, c1 * rOut, s1 * rOut, ro, go, bo, aOut);
            vertex(pose, consumer, c0 * rOut, s0 * rOut, ro, go, bo, aOut);
        }
    }

    private static void emitBirthFlash(PoseStack.Pose pose, VertexConsumer consumer, float t) {
        float duration = 14.0f;
        if (t >= duration) {
            return;
        }
        float p = t / duration;
        float alpha = (1.0f - p) * 0.9f;
        float len = 0.4f + 3.4f * (1.0f - p);
        float w = 0.10f + 0.05f * (1.0f - p);
        int spikes = 9;
        for (int i = 0; i < spikes; i++) {
            float ang = TAU * i / spikes + i * 0.618f;
            float c = Mth.cos(ang);
            float s = Mth.sin(ang);
            float px = -s * w;
            float py = c * w;
            vertex(pose, consumer, px, py, 1.0f, 0.95f, 0.85f, alpha);
            vertex(pose, consumer, -px, -py, 1.0f, 0.95f, 0.85f, alpha);
            vertex(pose, consumer, c * len - px, s * len - py, 1.0f, 0.55f, 0.20f, 0.0f);
            vertex(pose, consumer, c * len + px, s * len + py, 1.0f, 0.55f, 0.20f, 0.0f);
        }
    }

    private static void emitAccretionDisk(PoseStack.Pose pose, VertexConsumer consumer,
            float r, float t, float boost) {
        float rIn = r * 1.12f;
        float rOut = r * 3.4f;
        int radialSteps = 10;
        int angularSteps = 64;
        for (int i = 0; i < radialSteps; i++) {
            float rn0 = (float) i / radialSteps;
            float rn1 = (float) (i + 1) / radialSteps;
            float[] col0 = diskColor(rn0);
            float[] col1 = diskColor(rn1);
            for (int j = 0; j < angularSteps; j++) {
                float th0 = TAU * j / angularSteps;
                float th1 = TAU * (j + 1) / angularSteps;
                diskVertex(pose, consumer, rIn, rOut, rn0, th0, col0, t, boost);
                diskVertex(pose, consumer, rIn, rOut, rn0, th1, col0, t, boost);
                diskVertex(pose, consumer, rIn, rOut, rn1, th1, col1, t, boost);
                diskVertex(pose, consumer, rIn, rOut, rn1, th0, col1, t, boost);
            }
        }
    }

    private static void diskVertex(PoseStack.Pose pose, VertexConsumer consumer,
            float rIn, float rOut, float rn, float theta, float[] col, float t, float boost) {
        float rad = rIn + (rOut - rIn) * rn;
        float x = Mth.cos(theta) * rad;
        float z = Mth.sin(theta) * rad;
        // Keplerian differential rotation: inner gas orbits much faster
        float omega = 0.16f / (0.25f + rn * rn * 1.5f);
        float swirl = 0.80f
                + 0.20f * Mth.sin(3.0f * theta + 9.0f * rn - omega * t * 3.1f)
                + 0.12f * Mth.sin(7.0f * theta - 4.0f * rn - omega * t * 5.7f + 1.9f);
        // Doppler beaming: the approaching side glows hotter
        float doppler = 1.0f + 0.55f * Mth.sin(theta + t * 0.01f);
        float glow = swirl * doppler * boost;
        float alpha = Math.min(1.0f, col[3] * Math.min(1.6f, glow));
        consumer.addVertex(pose, x, 0.0f, z).setColor(
                Math.min(1.0f, col[0] * glow),
                Math.min(1.0f, col[1] * glow),
                Math.min(1.0f, col[2] * glow),
                alpha);
    }

    private static float[] diskColor(float rn) {
        for (int i = 0; i < DISK_STOPS.length - 1; i++) {
            float[] a = DISK_STOPS[i];
            float[] b = DISK_STOPS[i + 1];
            if (rn <= b[0]) {
                float f = (rn - a[0]) / (b[0] - a[0]);
                return new float[] {
                        Mth.lerp(f, a[1], b[1]),
                        Mth.lerp(f, a[2], b[2]),
                        Mth.lerp(f, a[3], b[3]),
                        Mth.lerp(f, a[4], b[4]),
                };
            }
        }
        float[] last = DISK_STOPS[DISK_STOPS.length - 1];
        return new float[] {last[1], last[2], last[3], last[4]};
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer consumer,
            float x, float y, float r, float g, float b, float a) {
        consumer.addVertex(pose, x, y, 0.0f).setColor(r, g, b, a);
    }
}
