package com.brendan.holograms.client;

import com.brendan.holograms.DisplayMode;
import com.brendan.holograms.HoloSigns;
import com.brendan.holograms.ProjectorBlockEntity;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * Turns a right-click on the floating hologram panel into a terminal.
 * <p>
 * The panel is drawn by a block entity renderer and has no block or entity behind it, so vanilla
 * gives the click nothing to hit: the editor opens on the projector block, or nothing happens at
 * all. This handler runs before vanilla processes the mouse button, tests the view ray against the
 * billboarded panel of every nearby linked projector, and opens the terminal screen when one is
 * under the crosshair. Cancelling the event keeps the block behind the hologram out of the way.
 */
@EventBusSubscriber(modid = HoloSigns.MOD_ID, value = Dist.CLIENT)
public final class HologramInteraction {
    /** Projectors whose hologram can be within click reach are in the surrounding chunks. */
    private static final int CHUNK_RADIUS = 3;

    private HologramInteraction() {
    }

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (event.isCanceled() || event.getAction() != InputConstants.PRESS) return;
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || minecraft.level == null || minecraft.player == null) return;
        if (minecraft.player.isSpectator() || minecraft.player.isSecondaryUseActive()) return;

        BlockPos hit = hitPanel(minecraft, minecraft.player);
        if (hit == null) return;
        minecraft.setScreen(new HologramTerminalScreen(hit));
        event.setCanceled(true);
    }

    private static @Nullable BlockPos hitPanel(Minecraft minecraft, LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F);
        double reach = player.blockInteractionRange();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        int centerX = player.chunkPosition().x();
        int centerZ = player.chunkPosition().z();
        for (int offsetX = -CHUNK_RADIUS; offsetX <= CHUNK_RADIUS; offsetX++) {
            for (int offsetZ = -CHUNK_RADIUS; offsetZ <= CHUNK_RADIUS; offsetZ++) {
                LevelChunk chunk = minecraft.level.getChunkSource().getChunkNow(centerX + offsetX, centerZ + offsetZ);
                if (chunk == null) continue;
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (!(blockEntity instanceof ProjectorBlockEntity projector)) continue;
                    if (!projector.isComputerLinked() || projector.getMode() != DisplayMode.MONITOR) continue;
                    if (projector.isProjectionObstructed()) continue;
                    double distance = ProjectorRenderer.panelHitDistance(projector, eye, look, reach);
                    if (distance < 0.0 || distance >= bestDistance) continue;
                    bestDistance = distance;
                    best = projector.getBlockPos();
                }
            }
        }
        return best;
    }
}
