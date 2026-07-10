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
 * Entity-side companion to {@link GargantuaCloudRenderer}.
 *
 * <p>The procedural material is drawn by the dedicated GPU pass. A small
 * vanilla-pipeline event horizon and photon ring are always submitted here as
 * a compatibility fallback, so shader failure never makes the Eye invisible.
 */
public class GargantuaRenderer extends EntityRenderer<GargantuaEntity, GargantuaRenderState> {
    private static final Identifier WHITE_TEXTURE =
            Identifier.fromNamespaceAndPath(BlackholeMod.MOD_ID, "textures/misc/white.png");
    private static final int FULL_BRIGHT = 0xF000F0;
    private static final float TAU = (float) (Math.PI * 2.0);
    private static final float ELEVATION_DEG = 26.0f;
    private static final int SPHERE_LAT = 16;
    private static final int SPHERE_LON = 24;
    private static final float[] SPHERE = buildSphere();

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

        Minecraft client = Minecraft.getInstance();
        Vec3 camera = client.gameRenderer.getMainCamera().position();
        float angleDeg = GargantuaEntity.apparentAngleAt(state.ageTicks);
        if (angleDeg >= 0.05f) {
            float distance = Mth.clamp(client.options.getEffectiveRenderDistance() * 16.0f * 0.7f,
                    64.0f, 300.0f);
            float radius = distance * (float) Math.tan(Math.toRadians(angleDeg));
            float yaw = (float) Math.toRadians(state.yaw);
            float elevation = (float) Math.toRadians(ELEVATION_DEG);
            Vec3 sight = new Vec3(-Mth.sin(yaw) * Mth.cos(elevation), Mth.sin(elevation),
                    Mth.cos(yaw) * Mth.cos(elevation));
            Vec3 anchor = camera.add(sight.scale(distance));
            Vec3 right = new Vec3(0.0, 1.0, 0.0).cross(sight).normalize();
            Vec3 up = sight.cross(right).normalize();

            poseStack.pushPose();
            poseStack.translate(anchor.x - state.anchorX, anchor.y - state.anchorY, anchor.z - state.anchorZ);

            collector.submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, vertices) -> {
                emitGlowRing(pose, vertices, right, up, radius * 1.005f, radius * 1.045f, 64,
                        1.0f, 0.94f, 0.78f, 0.90f);
                emitGlowRing(pose, vertices, right, up, radius * 1.115f, radius * 1.125f, 64,
                        1.0f, 0.82f, 0.55f, 0.32f);
            });
            collector.submitCustomGeometry(poseStack, RenderTypes.entitySolid(WHITE_TEXTURE),
                    (pose, vertices) -> emitSphere(pose, vertices, radius * 0.995f));
            poseStack.popPose();
        }

        float flash = flashAlphaAt(state.ageTicks);
        if (flash <= 0.001f) {
            return;
        }

        Quaternionf cameraRotation = new Quaternionf(client.gameRenderer.getMainCamera().rotation());
        Vector3f look = new Vector3f(0.0f, 0.0f, -1.0f).rotate(cameraRotation);
        poseStack.pushPose();
        poseStack.translate(
                camera.x + look.x * 3.0 - state.anchorX,
                camera.y + look.y * 3.0 - state.anchorY,
                camera.z + look.z * 3.0 - state.anchorZ);
        poseStack.mulPose(cameraRotation);
        collector.submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, vertices) -> {
            float alpha = Math.min(1.0f, flash);
            vertices.addVertex(pose, -12.0f, -12.0f, 0.0f).setColor(1.0f, 0.99f, 0.96f, alpha);
            vertices.addVertex(pose, 12.0f, -12.0f, 0.0f).setColor(1.0f, 0.99f, 0.96f, alpha);
            vertices.addVertex(pose, 12.0f, 12.0f, 0.0f).setColor(1.0f, 0.99f, 0.96f, alpha);
            vertices.addVertex(pose, -12.0f, 12.0f, 0.0f).setColor(1.0f, 0.99f, 0.96f, alpha);
        });
        poseStack.popPose();
    }

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

    private static float flashAlphaAt(float t) {
        if (t < GargantuaEntity.SETTLE_END) {
            return 0.0f;
        }
        if (t <= GargantuaEntity.FLASH_PEAK) {
            return phase(t, GargantuaEntity.SETTLE_END, GargantuaEntity.FLASH_PEAK);
        }
        if (t <= GargantuaEntity.FLASH_PEAK + 20.0f) {
            return 1.0f;
        }
        return 1.0f - phase(t, GargantuaEntity.FLASH_PEAK + 20.0f, GargantuaEntity.LIFETIME);
    }

    private static float[] buildSphere() {
        float[] data = new float[SPHERE_LAT * SPHERE_LON * 2 * 12];
        int index = 0;
        for (int latitude = 0; latitude < SPHERE_LAT; latitude++) {
            float theta0 = (float) Math.PI * latitude / SPHERE_LAT;
            float theta1 = (float) Math.PI * (latitude + 1) / SPHERE_LAT;
            for (int longitude = 0; longitude < SPHERE_LON; longitude++) {
                float phi0 = TAU * longitude / SPHERE_LON;
                float phi1 = TAU * (longitude + 1) / SPHERE_LON;
                float[][] vertices = {
                        spherePoint(theta0, phi0), spherePoint(theta1, phi0),
                        spherePoint(theta1, phi1), spherePoint(theta0, phi1),
                };
                for (int vertex = 0; vertex < 4; vertex++) {
                    data[index++] = vertices[vertex][0];
                    data[index++] = vertices[vertex][1];
                    data[index++] = vertices[vertex][2];
                }
                for (int vertex = 3; vertex >= 0; vertex--) {
                    data[index++] = vertices[vertex][0];
                    data[index++] = vertices[vertex][1];
                    data[index++] = vertices[vertex][2];
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

    private static void emitSphere(PoseStack.Pose pose, VertexConsumer vertices, float radius) {
        for (int index = 0; index < SPHERE.length; index += 3) {
            float x = SPHERE[index];
            float y = SPHERE[index + 1];
            float z = SPHERE[index + 2];
            vertices.addVertex(pose, x * radius, y * radius, z * radius)
                    .setColor(1, 1, 3, 255)
                    .setUv(0.5f, 0.5f)
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(FULL_BRIGHT)
                    .setNormal(pose, x, y, z);
        }
    }

    private static void emitGlowRing(PoseStack.Pose pose, VertexConsumer vertices,
            Vec3 axisX, Vec3 axisY, float innerRadius, float outerRadius, int segments,
            float red, float green, float blue, float alpha) {
        for (int segment = 0; segment < segments; segment++) {
            float angle0 = TAU * segment / segments;
            float angle1 = TAU * (segment + 1) / segments;
            ringVertex(pose, vertices, axisX, axisY, angle0, innerRadius, red, green, blue, alpha);
            ringVertex(pose, vertices, axisX, axisY, angle1, innerRadius, red, green, blue, alpha);
            ringVertex(pose, vertices, axisX, axisY, angle1, outerRadius, red, green, blue, 0.0f);
            ringVertex(pose, vertices, axisX, axisY, angle0, outerRadius, red, green, blue, 0.0f);
        }
    }

    private static void ringVertex(PoseStack.Pose pose, VertexConsumer vertices,
            Vec3 axisX, Vec3 axisY, float angle, float radius,
            float red, float green, float blue, float alpha) {
        float cosine = Mth.cos(angle);
        float sine = Mth.sin(angle);
        vertices.addVertex(pose,
                (float) (axisX.x * cosine + axisY.x * sine) * radius,
                (float) (axisX.y * cosine + axisY.y * sine) * radius,
                (float) (axisX.z * cosine + axisY.z * sine) * radius)
                .setColor(red, green, blue, alpha);
    }
}
