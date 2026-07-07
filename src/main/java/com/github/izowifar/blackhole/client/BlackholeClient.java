package com.github.izowifar.blackhole.client;

import com.github.izowifar.blackhole.BlackholeMod;
import com.github.izowifar.blackhole.client.render.BlackHoleRenderer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class BlackholeClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(BlackholeMod.BLACK_HOLE, BlackHoleRenderer::new);
    }
}
