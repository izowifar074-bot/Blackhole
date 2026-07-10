package com.github.izowifar.blackhole.client.render;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import com.github.izowifar.blackhole.BlackholeMod;
import com.github.izowifar.blackhole.entity.GargantuaEntity;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * GPU material pass for the Eye of Gargantua.
 *
 * <p>A single camera-facing world quad carries the whole procedural image. Its
 * fragment shader is the Minecraft port of the approved "cinematic balance"
 * WebGL prototype: polar Keplerian flow, five-octave domain-warped clouds,
 * filament-bound grains, Doppler colour separation, a lensed halo and a soft
 * in-shader bloom approximation. Keeping the material in one pass also avoids
 * the perspective seams and giant texture hoops produced by stacked annuli.
 */
public final class GargantuaCloudRenderer implements AutoCloseable {
    private static final float ELEVATION_DEG = 26.0f;
    private static final float QUAD_RADIUS = 5.32f;

    private static final RenderPipeline PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(BlackholeMod.id("pipeline/gargantua_cloud"))
                    .withVertexShader(BlackholeMod.id("gargantua_cloud"))
                    .withFragmentShader(BlackholeMod.id("gargantua_cloud"))
                    .withUniform("GargantuaParams", UniformType.UNIFORM_BUFFER)
                    .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .withCull(false)
                    .withDepthWrite(false)
                    .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                    .build());

    private static final Vector4f COLOR_MODULATOR = new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static final int PARAM_BYTES = new Std140SizeCalculator()
            .putVec4().putVec4().putVec4().putVec4().get();

    private static final GargantuaCloudRenderer INSTANCE = new GargantuaCloudRenderer();

    private final ByteBufferBuilder allocator = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
    private final MappableRingBuffer paramsBuffer = new MappableRingBuffer(
            () -> "blackhole gargantua material parameters",
            GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
            PARAM_BYTES);
    private BufferBuilder buffer;
    private MappableRingBuffer vertexBuffer;

    private GargantuaCloudRenderer() {
    }

    public static void register() {
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(INSTANCE::render);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> INSTANCE.close());
    }

    private void render(WorldRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) {
            return;
        }

        List<GargantuaEntity> events = client.level.getEntitiesOfClass(
                GargantuaEntity.class, client.player.getBoundingBox().inflate(512.0));
        if (events.isEmpty()) {
            return;
        }

        // Only one Eye event is intended at a time. Choosing the newest keeps
        // accidental duplicate summons from multiplying the expensive pass.
        GargantuaEntity entity = events.get(0);
        for (GargantuaEntity candidate : events) {
            if (candidate.getAgeTicks() < entity.getAgeTicks()) {
                entity = candidate;
            }
        }

        float t = entity.getAgeTicks();
        float angleDeg = GargantuaEntity.apparentAngleAt(t);
        if (angleDeg < 0.05f) {
            return;
        }

        float distance = Mth.clamp(client.options.getEffectiveRenderDistance() * 16.0f * 0.7f, 64.0f, 300.0f);
        float shadowRadius = distance * (float) Math.tan(Math.toRadians(angleDeg));

        Vec3 camera = context.worldState().cameraRenderState.pos;
        float yaw = (float) Math.toRadians(entity.getYRot());
        float elevation = (float) Math.toRadians(ELEVATION_DEG);
        Vec3 sight = new Vec3(-Mth.sin(yaw) * Mth.cos(elevation), Mth.sin(elevation),
                Mth.cos(yaw) * Mth.cos(elevation));
        Vec3 center = camera.add(sight.scale(distance));
        Vec3 right = new Vec3(0.0, 1.0, 0.0).cross(sight).normalize();
        Vec3 up = sight.cross(right).normalize();

        PoseStack matrices = context.matrices();
        matrices.pushPose();
        matrices.translate(-camera.x, -camera.y, -camera.z);
        if (this.buffer == null) {
            this.buffer = new BufferBuilder(this.allocator, PIPELINE.getVertexFormatMode(), PIPELINE.getVertexFormat());
        }
        emitQuad(matrices.last().pose(), this.buffer, center, right, up, shadowRadius * QUAD_RADIUS);
        matrices.popPose();

        MeshData mesh = this.buffer.buildOrThrow();
        this.buffer = null;
        MeshData.DrawState drawState = mesh.drawState();
        VertexFormat format = drawState.format();
        GpuBuffer vertices = upload(mesh, drawState, format);

        float opening = openingAt(t);
        float divider = dividerAt(t);
        float roll = (float) Math.toRadians(rollDegAt(t));
        float brightness = brightnessAt(t);
        float doppler = Mth.lerp(phase(t, GargantuaEntity.EMERGE_END,
                GargantuaEntity.SETTLE_END - 50.0f), 0.45f, 0.72f);
        float blaze = phase(t, 500.0f, GargantuaEntity.FLASH_PEAK);
        float opacity = phase(t, 0.0f, GargantuaEntity.EMERGE_END);
        float materialTime = (float) (System.nanoTime() * 1.0e-9);

        this.paramsBuffer.rotate();
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        try (GpuBuffer.MappedView view = encoder.mapBuffer(this.paramsBuffer.currentBuffer(), false, true)) {
            Std140Builder.intoBuffer(view.data())
                    // Approved WebGL "cinematic balance" preset.
                    .putVec4(materialTime, 1.08f, 0.78f, 0.64f)
                    .putVec4(0.62f, 0.46f, doppler, 1.04f)
                    .putVec4(opening, divider, roll, brightness)
                    .putVec4(blaze, 1.18f, opacity, 0.0f);
        }

        draw(client, mesh, drawState, vertices, format);
        this.vertexBuffer.rotate();
    }

    private GpuBuffer upload(MeshData mesh, MeshData.DrawState drawState, VertexFormat format) {
        int required = drawState.vertexCount() * format.getVertexSize();
        if (this.vertexBuffer == null || this.vertexBuffer.size() < required) {
            if (this.vertexBuffer != null) {
                this.vertexBuffer.close();
            }
            this.vertexBuffer = new MappableRingBuffer(
                    () -> "blackhole gargantua material vertices",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE,
                    required);
        }

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        try (GpuBuffer.MappedView view = encoder.mapBuffer(
                this.vertexBuffer.currentBuffer().slice(0, mesh.vertexBuffer().remaining()), false, true)) {
            MemoryUtil.memCopy(mesh.vertexBuffer(), view.data());
        }
        return this.vertexBuffer.currentBuffer();
    }

    private void draw(Minecraft client, MeshData mesh, MeshData.DrawState drawState,
            GpuBuffer vertices, VertexFormat format) {
        GpuBuffer indices;
        VertexFormat.IndexType indexType;
        if (PIPELINE.getVertexFormatMode() == VertexFormat.Mode.QUADS) {
            mesh.sortQuads(this.allocator, RenderSystem.getProjectionType().vertexSorting());
            indices = PIPELINE.getVertexFormat().uploadImmediateIndexBuffer(mesh.indexBuffer());
            indexType = mesh.drawState().indexType();
        } else {
            RenderSystem.AutoStorageIndexBuffer sequential =
                    RenderSystem.getSequentialBuffer(PIPELINE.getVertexFormatMode());
            indices = sequential.getBuffer(drawState.indexCount());
            indexType = sequential.type();
        }

        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(
                RenderSystem.getModelViewMatrix(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "blackhole gargantua cloud pass",
                client.getMainRenderTarget().getColorTextureView(), OptionalInt.empty(),
                client.getMainRenderTarget().getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(PIPELINE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            pass.setUniform("GargantuaParams", this.paramsBuffer.currentBuffer());
            pass.setVertexBuffer(0, vertices);
            pass.setIndexBuffer(indices, indexType);
            pass.drawIndexed(0 / format.getVertexSize(), 0, drawState.indexCount(), 1);
        }
        mesh.close();
    }

    private static void emitQuad(Matrix4fc matrix, BufferBuilder builder, Vec3 center,
            Vec3 right, Vec3 up, float halfSize) {
        Vec3 rx = right.scale(halfSize);
        Vec3 uy = up.scale(halfSize);
        Vec3 bottomLeft = center.subtract(rx).subtract(uy);
        Vec3 bottomRight = center.add(rx).subtract(uy);
        Vec3 topRight = center.add(rx).add(uy);
        Vec3 topLeft = center.subtract(rx).add(uy);

        // RG carries the interpolated material UV without requiring a texture.
        builder.addVertex(matrix, (float) bottomLeft.x, (float) bottomLeft.y, (float) bottomLeft.z)
                .setColor(0, 0, 0, 255);
        builder.addVertex(matrix, (float) bottomRight.x, (float) bottomRight.y, (float) bottomRight.z)
                .setColor(255, 0, 0, 255);
        builder.addVertex(matrix, (float) topRight.x, (float) topRight.y, (float) topRight.z)
                .setColor(255, 255, 0, 255);
        builder.addVertex(matrix, (float) topLeft.x, (float) topLeft.y, (float) topLeft.z)
                .setColor(0, 255, 0, 255);
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

    /** Edge-on in the first act, opening during the orbit, edge-on at p10. */
    private static float openingAt(float t) {
        if (t < GargantuaEntity.PLANE_END) {
            return 0.035f;
        }
        if (t < GargantuaEntity.SWING_END) {
            return Mth.lerp(phase(t, GargantuaEntity.PLANE_END, GargantuaEntity.SWING_END), 0.035f, 0.48f);
        }
        return Mth.lerp(phase(t, GargantuaEntity.SWING_END, GargantuaEntity.SETTLE_END), 0.48f, 0.040f);
    }

    /** Positive shifts the divider down (upper-left lobe larger). */
    private static float dividerAt(float t) {
        if (t < GargantuaEntity.EMERGE_END) {
            return 0.25f;
        }
        if (t < GargantuaEntity.PLANE_END) {
            return Mth.lerp(phase(t, GargantuaEntity.EMERGE_END, GargantuaEntity.PLANE_END), 0.25f, -0.22f);
        }
        if (t < GargantuaEntity.SWING_END) {
            return Mth.lerp(phase(t, GargantuaEntity.PLANE_END, GargantuaEntity.SWING_END), -0.22f, -0.04f);
        }
        return Mth.lerp(phase(t, GargantuaEntity.SWING_END, GargantuaEntity.SETTLE_END), -0.04f, 0.0f);
    }

    private static float rollDegAt(float t) {
        return Mth.lerp(phase(t, GargantuaEntity.PLANE_END, GargantuaEntity.SWING_END), 24.0f, 10.0f)
                + 0.4f * Mth.sin(t * 0.008f);
    }

    private static float brightnessAt(float t) {
        return Mth.lerp(phase(t, 0.0f, GargantuaEntity.SETTLE_END), 0.70f, 1.35f);
    }

    @Override
    public void close() {
        this.allocator.close();
        this.paramsBuffer.close();
        if (this.vertexBuffer != null) {
            this.vertexBuffer.close();
            this.vertexBuffer = null;
        }
    }
}
