package com.brendan.holograms.client;

import com.brendan.holograms.ProjectorBlock;
import com.brendan.holograms.ProjectorBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class ProjectorRenderer implements BlockEntityRenderer<ProjectorBlockEntity, ProjectorRenderState> {
    private final Font font;

    public ProjectorRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.font();
    }

    @Override
    public ProjectorRenderState createRenderState() {
        return new ProjectorRenderState();
    }

    @Override
    public void extractRenderState(ProjectorBlockEntity blockEntity, ProjectorRenderState state, float partialTick,
                                   Vec3 cameraPos, @Nullable ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, partialTick, cameraPos, crumblingOverlay);
        state.lines = blockEntity.getLines();
        state.facing = blockEntity.getBlockState().getValue(ProjectorBlock.FACING);
    }

    @Override
    public void submit(ProjectorRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                       CameraRenderState cameraState) {
        if (state.lines.isEmpty()) return;

        poseStack.pushPose();
        // Anchor the hologram above the projector instead of on its front face.
        poseStack.translate(0.5, 1.62, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(yawFor(state.facing)));
        poseStack.translate(0.0, 0.0, -0.52);
        poseStack.scale(1.0F / 36.0F, -1.0F / 36.0F, 1.0F / 36.0F);

        float firstY = -((state.lines.size() - 1) * 10.0F) / 2.0F;
        for (int i = 0; i < state.lines.size(); i++) {
            String line = state.lines.get(i);
            if (line.isEmpty()) continue;
            float width = this.font.width(line);
            collector.submitText(
                    poseStack,
                    -width / 2.0F,
                    firstY + i * 10.0F,
                    Component.literal(line).getVisualOrderText(),
                    false,
                    Font.DisplayMode.SEE_THROUGH,
                    state.lightCoords,
                    0xFF7FFFD4,
                    0,
                    0
            );
        }
        poseStack.popPose();
    }

    private static float yawFor(Direction facing) {
        return switch (facing) {
            case NORTH -> 0.0F;
            case EAST -> 90.0F;
            case SOUTH -> 180.0F;
            case WEST -> 270.0F;
            default -> 0.0F;
        };
    }
}
