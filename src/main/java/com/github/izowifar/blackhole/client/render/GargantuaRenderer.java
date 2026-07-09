package com.github.izowifar.blackhole.client.render;

import com.github.izowifar.blackhole.BlackholeMod;
import com.github.izowifar.blackhole.entity.GargantuaEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Paints Gargantua onto the sky. All geometry is anchored at a fixed direction
 * from the camera (given by the entity's yaw at summon time), so the hole
 * hangs in the sky and the player can look around freely.
 *
 * <p>Layers, back to front: a faint blue back-glow, the pitch-black event
 * horizon sphere, the world-space accretion disk (three concentric bands of
 * the granular strip texture, Keplerian differential rotation, doppler-boosted
 * on the approaching side), the camera-perpendicular lensed halo carrying the
 * same texture up and over the shadow, two photon rings (a blazing rim plus
 * the delicate second-order hairline), a growing doppler blaze, and finally
 * the consumption white-out drawn as a screen-space billboard.
 *
 * <p>The view "descends toward the disk plane" over the approach phase: the
 * disk tilt animates from 22 degrees down to 4 while the whole system slowly
 * rolls, reproducing the drift of perspective across the reference shots.
 */
public class GargantuaRenderer extends EntityRenderer<GargantuaEntity, GargantuaRenderState> {
    private static final Identifier STRIP_TEXTURE =
            Identifier.fromNamespaceAndPath(BlackholeMod.MOD_ID, "textures/misc/gargantua_strip.png");
    private static final Identifier WHITE_TEXTURE =
            Identifier.fromNamespaceAndPath(BlackholeMod.MOD_ID, "textures/misc/white.png");
    private static final int FULL_BRIGHT = 0xF000F0;
    private static final float TAU = (float) (Math.PI * 2.0);
    private static final float ELEVATION_DEG = 26.0f;

    private static final int SPHERE_LAT = 24;
    private static final int SPHERE_LON = 40;
    private static final float[] SPHERE = buildSphere();

    /** Disk bands: inner/outer radius in units of the shadow radius R. */
    private static final float[][] DISK_BANDS = {
            {1.10f, 1.75f},
            {1.75f, 2.60f},
            {2.60f, 3.80f},
    };
    /** Texture scroll speed per band; inner gas orbits faster (Keplerian). */
    private static final float[] BAND_SPEEDS = {0.0016f, 0.0009f, 0.0005f};

    public GargantuaRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public GargantuaRenderState createRenderState() {
        return new GargantuaRenderState();
    }

    @Override
    public void extractRenderState(GargantuaEntity entity, GargantuaRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.ageTicks = entity.getAgeTicks() + partialTick;
        state.yaw = entity.getYRot();
        state.anchorX = entity.getX();
        state.anchorY = entity.getY();
        state.anchorZ = entity.getZ();
    }

    @Override
    public boolean shouldRender(GargantuaEntity entity, Frustum frustum, double x, double y, double z) {
        return true;
    }

    @Override
    public void submit(GargantuaRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
            CameraRenderState cameraState) {
        super.submit(state, poseStack, collector, cameraState);
        float t = state.ageTicks;
        float angleDeg = GargantuaEntity.apparentAngleAt(t);
        if (angleDeg < 0.05f) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        float dist = Mth.clamp(mc.options.getEffectiveRenderDistance() * 16.0f * 0.7f, 64.0f, 300.0f);
        float radius = dist * (float) Math.tan(Math.toRadians(angleDeg));

        Vec3 camPos = mc.gameRenderer.getMainCamera().position();
        float yawRad = (float) Math.toRadians(state.yaw);
        float el = (float) Math.toRadians(ELEVATION_DEG);
        Vec3 fwd = new Vec3(-Mth.sin(yawRad) * Mth.cos(el), Mth.sin(el), Mth.cos(yawRad) * Mth.cos(el));
        Vec3 anchor = camPos.add(fwd.scale(dist));

        // world frame perpendicular to the line of sight toward the hole
        Vec3 rightW = new Vec3(0, 1, 0).cross(fwd).normalize();
        Vec3 upW = fwd.cross(rightW).normalize();
        float roll = (float) Math.toRadians(rollDegAt(t));
        Vec3 right = rightW.scale(Mth.cos(roll)).add(upW.scale(Mth.sin(roll)));
        Vec3 up = rightW.scale(-Mth.sin(roll)).add(upW.scale(Mth.cos(roll)));
        // disk plane: contains 'right'; second axis leans from screen-facing
        // toward the view axis as tilt decreases (edge-on)
        float tilt = (float) Math.toRadians(tiltDegAt(t));
        Vec3 diskAxis = fwd.scale(Mth.cos(tilt)).add(up.scale(Mth.sin(tilt)));

        float brightness = brightnessAt(t);
        float doppler = dopplerAmpAt(t);
        float blaze = blazeAt(t);
        float flash = flashAlphaAt(t);

        poseStack.pushPose();
        poseStack.translate(anchor.x - state.anchorX, anchor.y - state.anchorY, anchor.z - state.anchorZ);

        float r = radius;
        Vec3 e1 = right;
        Vec3 e2d = diskAxis;
        Vec3 e2h = up;

        // 1. faint blue back-glow suggesting lensed starlight
        collector.submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, vc) -> {
            emitGlowRing(pose, vc, e1, e2h, r * 1.00f, r * 2.80f, 64,
                    0.35f, 0.45f, 0.85f, 0.10f * brightness);
            // doppler blaze: the approaching side floods with white light
            if (blaze > 0.001f) {
                Vec3 center = e1.scale(-r * 1.55f);
                emitBlaze(pose, vc, center, e1, e2h,
                        r * (0.8f + 1.5f * blaze), Math.min(1.0f, 0.55f * blaze));
            }
            // photon rings: blazing rim + second-order hairline
            emitGlowRing(pose, vc, e1, e2h, r * 1.005f, r * 1.045f, 96,
                    1.00f, 0.97f, 0.88f, Math.min(1.0f, 0.95f * brightness));
            emitGlowRing(pose, vc, e1, e2h, r * 1.120f, r * 1.128f, 96,
                    1.00f, 0.95f, 0.85f, 0.35f);
        });

        // 2. the event horizon
        collector.submitCustomGeometry(poseStack, RenderTypes.entitySolid(WHITE_TEXTURE),
                (pose, vc) -> emitSphere(pose, vc, r * 0.995f));

        // 3 + 4. granular accretion disk and the lensed halo
        collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(STRIP_TEXTURE),
                (pose, vc) -> {
                    for (int band = 0; band < DISK_BANDS.length; band++) {
                        emitTexturedAnnulus(pose, vc, e1, e2d,
                                r * DISK_BANDS[band][0], r * DISK_BANDS[band][1], 96, 6,
                                t * BAND_SPEEDS[band] + band * 0.37f, 3.0f,
                                brightness, doppler, 0.0f, 1.0f);
                    }
                    // halo hugging the shadow, carrying the disk light overhead
                    emitTexturedAnnulus(pose, vc, e1, e2h,
                            r * 1.03f, r * 1.95f, 96, 6,
                            -t * 0.0007f, 2.0f,
                            brightness * 1.15f, doppler * 0.7f, 0.6f, 0.9f);
                });

        poseStack.popPose();

        // 7. consumption white-out: a screen-space billboard in front of the camera
        if (flash > 0.001f) {
            Quaternionf camRot = new Quaternionf(mc.gameRenderer.getMainCamera().rotation());
            Vector3f look = new Vector3f(0, 0, -1).rotate(camRot);
            poseStack.pushPose();
            poseStack.translate(
                    camPos.x + look.x * 3.0 - state.anchorX,
                    camPos.y + look.y * 3.0 - state.anchorY,
                    camPos.z + look.z * 3.0 - state.anchorZ);
            poseStack.mulPose(camRot);
            float a = Math.min(1.0f, flash);
            collector.submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, vc) -> {
                vertexPC(pose, vc, -12, -12, 0, 1.0f, 0.99f, 0.96f, a);
                vertexPC(pose, vc, 12, -12, 0, 1.0f, 0.99f, 0.96f, a);
                vertexPC(pose, vc, 12, 12, 0, 1.0f, 0.99f, 0.96f, a);
                vertexPC(pose, vc, -12, 12, 0, 1.0f, 0.99f, 0.96f, a);
            });
            poseStack.popPose();
        }
    }

    // ---------------------------------------------------------------- timeline

    private static float phase(float t, float start, float end) {
        if (t <= start) {
            return 0.0f;
        }
        if (t >= end) {
            return 1.0f;
        }
        float p = (t - start) / (end - start);
        return p * p * (3.0f - 2.0f * p);
    }

    private static float tiltDegAt(float t) {
        return Mth.lerp(phase(t, GargantuaEntity.EMERGE_END * 0.5f, GargantuaEntity.DOMINANCE_END), 22.0f, 4.0f);
    }

    private static float rollDegAt(float t) {
        return Mth.lerp(phase(t, 0, GargantuaEntity.LIFETIME), -14.0f, 6.0f)
                + 0.6f * Mth.sin(t * 0.008f);
    }

    private static float brightnessAt(float t) {
        return Mth.lerp(phase(t, 0, GargantuaEntity.DOMINANCE_END), 0.55f, 1.35f);
    }

    private static float dopplerAmpAt(float t) {
        return Mth.lerp(phase(t, GargantuaEntity.EMERGE_END, GargantuaEntity.DOMINANCE_END), 0.35f, 0.85f);
    }

    private static float blazeAt(float t) {
        return phase(t, 700.0f, GargantuaEntity.FLASH_PEAK);
    }

    private static float flashAlphaAt(float t) {
        if (t < GargantuaEntity.DOMINANCE_END) {
            return 0.0f;
        }
        if (t <= GargantuaEntity.FLASH_PEAK) {
            return phase(t, GargantuaEntity.DOMINANCE_END, GargantuaEntity.FLASH_PEAK);
        }
        if (t <= GargantuaEntity.FLASH_PEAK + 20) {
            return 1.0f;
        }
        return 1.0f - phase(t, GargantuaEntity.FLASH_PEAK + 20, GargantuaEntity.LIFETIME);
    }

    // ---------------------------------------------------------------- geometry

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

    private static void emitSphere(PoseStack.Pose pose, VertexConsumer vc, float radius) {
        for (int i = 0; i < SPHERE.length; i += 3) {
            float x = SPHERE[i];
            float y = SPHERE[i + 1];
            float z = SPHERE[i + 2];
            vc.addVertex(pose, x * radius, y * radius, z * radius)
                    .setColor(1, 1, 3, 255)
                    .setUv(0.5f, 0.5f)
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(FULL_BRIGHT)
                    .setNormal(pose, x, y, z);
        }
    }

    /**
     * Annulus in the plane spanned by e1/e2, textured with the granular strip.
     * U runs around the ring ({@code uWrap} repeats plus a scroll offset for
     * rotation), V runs from the inner edge (the strip's blazing rim) outward.
     * Per-vertex doppler boost brightens the approaching side.
     */
    private static void emitTexturedAnnulus(PoseStack.Pose pose, VertexConsumer vc,
            Vec3 e1, Vec3 e2, float rIn, float rOut, int segs, int rings,
            float uOffset, float uWrap,
            float brightness, float dopplerAmp, float dopplerPhase, float alphaMul) {
        for (int i = 0; i < rings; i++) {
            float v0 = (float) i / rings;
            float v1 = (float) (i + 1) / rings;
            float r0 = Mth.lerp(v0, rIn, rOut);
            float r1 = Mth.lerp(v1, rIn, rOut);
            for (int j = 0; j < segs; j++) {
                float f0 = (float) j / segs;
                float f1 = (float) (j + 1) / segs;
                float a0 = TAU * f0;
                float a1 = TAU * f1;
                float u0 = uWrap * f0 + uOffset;
                float u1 = uWrap * f1 + uOffset;
                texturedVertex(pose, vc, e1, e2, a0, r0, u0, v0, brightness, dopplerAmp, dopplerPhase, alphaMul);
                texturedVertex(pose, vc, e1, e2, a1, r0, u1, v0, brightness, dopplerAmp, dopplerPhase, alphaMul);
                texturedVertex(pose, vc, e1, e2, a1, r1, u1, v1, brightness, dopplerAmp, dopplerPhase, alphaMul);
                texturedVertex(pose, vc, e1, e2, a0, r1, u0, v1, brightness, dopplerAmp, dopplerPhase, alphaMul);
            }
        }
    }

    private static void texturedVertex(PoseStack.Pose pose, VertexConsumer vc,
            Vec3 e1, Vec3 e2, float angle, float radius, float u, float v,
            float brightness, float dopplerAmp, float dopplerPhase, float alphaMul) {
        float ca = Mth.cos(angle);
        float sa = Mth.sin(angle);
        float x = (float) (e1.x * ca + e2.x * sa) * radius;
        float y = (float) (e1.y * ca + e2.y * sa) * radius;
        float z = (float) (e1.z * ca + e2.z * sa) * radius;
        float dop = 1.0f + dopplerAmp * (-Mth.cos(angle + dopplerPhase));
        float g = brightness * dop;
        vc.addVertex(pose, x, y, z)
                .setColor(Math.min(1.0f, g), Math.min(1.0f, g * 0.97f), Math.min(1.0f, g * 0.90f),
                        Math.min(1.0f, alphaMul * (0.55f + 0.45f * dop)))
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT)
                .setNormal(pose, 0, 1, 0);
    }

    /** Additive position-color ring fading from an inner color to transparent. */
    private static void emitGlowRing(PoseStack.Pose pose, VertexConsumer vc,
            Vec3 e1, Vec3 e2, float rIn, float rOut, int segs,
            float red, float green, float blue, float alphaIn) {
        for (int j = 0; j < segs; j++) {
            float a0 = TAU * j / segs;
            float a1 = TAU * (j + 1) / segs;
            planarPC(pose, vc, e1, e2, a0, rIn, red, green, blue, alphaIn);
            planarPC(pose, vc, e1, e2, a1, rIn, red, green, blue, alphaIn);
            planarPC(pose, vc, e1, e2, a1, rOut, red, green, blue, 0.0f);
            planarPC(pose, vc, e1, e2, a0, rOut, red, green, blue, 0.0f);
        }
    }

    /** Radial white-amber gradient disc used for the doppler blaze. */
    private static void emitBlaze(PoseStack.Pose pose, VertexConsumer vc,
            Vec3 center, Vec3 e1, Vec3 e2, float radius, float alpha) {
        int segs = 48;
        for (int j = 0; j < segs; j++) {
            float a0 = TAU * j / segs;
            float a1 = TAU * (j + 1) / segs;
            // triangle fan emitted as quads with a degenerate center edge
            blazeVertex(pose, vc, center, e1, e2, a0, 0, 1.0f, 0.98f, 0.92f, alpha);
            blazeVertex(pose, vc, center, e1, e2, a1, 0, 1.0f, 0.98f, 0.92f, alpha);
            blazeVertex(pose, vc, center, e1, e2, a1, radius, 1.0f, 0.75f, 0.40f, 0.0f);
            blazeVertex(pose, vc, center, e1, e2, a0, radius, 1.0f, 0.75f, 0.40f, 0.0f);
        }
    }

    private static void blazeVertex(PoseStack.Pose pose, VertexConsumer vc,
            Vec3 center, Vec3 e1, Vec3 e2, float angle, float radius,
            float red, float green, float blue, float alpha) {
        float ca = Mth.cos(angle);
        float sa = Mth.sin(angle);
        vertexPC(pose, vc,
                (float) (center.x + (e1.x * ca + e2.x * sa) * radius),
                (float) (center.y + (e1.y * ca + e2.y * sa) * radius),
                (float) (center.z + (e1.z * ca + e2.z * sa) * radius),
                red, green, blue, alpha);
    }

    private static void planarPC(PoseStack.Pose pose, VertexConsumer vc,
            Vec3 e1, Vec3 e2, float angle, float radius,
            float red, float green, float blue, float alpha) {
        float ca = Mth.cos(angle);
        float sa = Mth.sin(angle);
        vertexPC(pose, vc,
                (float) (e1.x * ca + e2.x * sa) * radius,
                (float) (e1.y * ca + e2.y * sa) * radius,
                (float) (e1.z * ca + e2.z * sa) * radius,
                red, green, blue, alpha);
    }

    private static void vertexPC(PoseStack.Pose pose, VertexConsumer vc,
            float x, float y, float z, float red, float green, float blue, float alpha) {
        vc.addVertex(pose, x, y, z).setColor(red, green, blue, alpha);
    }
}
