package com.brendan.holograms.client;

import com.brendan.holograms.HoloSigns;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = HoloSigns.MOD_ID, value = Dist.CLIENT)
public final class HoloSignsClient {
    private HoloSignsClient() {}

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(HoloSigns.PROJECTOR_ENTITY.get(), ProjectorRenderer::new);
    }
}
