package com.github.izowifar.blackhole.client.render;

import com.github.izowifar.blackhole.entity.GargantuaEntity;
import com.mojang.blaze3d.vertex.PoseStack;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.world.phys.Vec3;

/**
 * Entity-side companion to {@link GargantuaCloudRenderer}.
 *
 * <p>The procedural black hole itself is drawn by the dedicated GPU world
 * pass. This renderer only owns the final camera white-out so the server
 * choreography and the existing entity render lifecycle remain unchanged.
 */
public class GargantuaRenderer extends EntityRenderer<GargantuaEntity, GargantuaRenderState> {
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
        float flash = flashAlphaAt(state.ageTicks);
        if (flash <= 0.001f) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        Vec3 camera = client.gameRenderer.getMainCamera().position();
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
}
