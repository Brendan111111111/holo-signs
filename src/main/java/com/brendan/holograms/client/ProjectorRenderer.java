package com.brendan.holograms.client;

import com.brendan.holograms.DisplayMode;
import com.brendan.holograms.ProjectorBlockEntity;
import com.brendan.holograms.TerminalBuffer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class ProjectorRenderer implements BlockEntityRenderer<ProjectorBlockEntity, ProjectorRenderState> {
    private static final float WORLD_SCALE = 1.0F / 36.0F;
    private static final float LINE_HEIGHT = 10.0F;
    private static final float CELL_WIDTH = 6.0F;
    private static final float CELL_HEIGHT = 9.0F;
    private static final int LINE_COLOR = 0xFF7FFFD4;

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
        state.facing = blockEntity.getFacing();
        state.distance = blockEntity.getDistance();

        TerminalBuffer terminal = blockEntity.getTerminal();
        state.monitor = blockEntity.getMode() == DisplayMode.MONITOR && terminal.hasText();
        state.columns = terminal.getColumns();
        state.rows = terminal.getRows();
        state.monitorRows = terminal.textRows();
        state.monitorForeground = terminal.foregroundData();
    }

    @Override
    public void submit(ProjectorRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                       CameraRenderState cameraState) {
        if (!state.monitor && state.lines.isEmpty()) return;

        poseStack.pushPose();
        // Holograms are billboarded with the camera orientation, exactly like name tags. This keeps
        // the text upright and readable no matter which face the projector is mounted on or where
        // the viewer stands, including straight above or below a ceiling/floor projector.
        poseStack.translate(
                0.5 + state.facing.getStepX() * (0.5 + state.distance),
                0.5 + state.facing.getStepY() * (0.5 + state.distance),
                0.5 + state.facing.getStepZ() * (0.5 + state.distance));
        poseStack.mulPose(cameraState.orientation);
        poseStack.scale(WORLD_SCALE, -WORLD_SCALE, WORLD_SCALE);

        if (state.monitor) {
            submitMonitor(state, poseStack, collector);
        } else {
            submitLines(state, poseStack, collector);
        }
        poseStack.popPose();
    }

    private void submitLines(ProjectorRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
        float totalHeight = state.lines.size() * LINE_HEIGHT;
        for (int index = 0; index < state.lines.size(); index++) {
            String line = state.lines.get(index);
            if (line.isEmpty()) continue;
            float width = this.font.width(line);
            collector.submitText(
                    poseStack,
                    -width / 2.0F,
                    -totalHeight / 2.0F + index * LINE_HEIGHT,
                    Component.literal(line).getVisualOrderText(),
                    false,
                    Font.DisplayMode.SEE_THROUGH,
                    state.lightCoords,
                    LINE_COLOR,
                    0,
                    0
            );
        }
    }

    private void submitMonitor(ProjectorRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
        int columns = state.columns;
        int rows = state.rows;
        if (columns <= 0 || rows <= 0) return;
        float totalWidth = columns * CELL_WIDTH;
        float totalHeight = rows * CELL_HEIGHT;

        for (int row = 0; row < rows; row++) {
            String text = row < state.monitorRows.size() ? state.monitorRows.get(row) : "";
            float y = -totalHeight / 2.0F + row * CELL_HEIGHT;
            // One glyph per cell keeps columns aligned exactly like a monitor, even though the
            // vanilla font is proportional.
            for (int column = 0; column < columns; column++) {
                char character = column < text.length() ? text.charAt(column) : ' ';
                if (character == ' ') continue;
                int cell = row * columns + column;
                int color = cell < state.monitorForeground.length ? state.monitorForeground[cell] & 0xFFFFFF : 0xFFFFFF;
                collector.submitText(
                        poseStack,
                        -totalWidth / 2.0F + column * CELL_WIDTH,
                        y,
                        Component.literal(String.valueOf(character)).getVisualOrderText(),
                        false,
                        Font.DisplayMode.SEE_THROUGH,
                        state.lightCoords,
                        0xFF000000 | color,
                        0,
                        0
                );
            }
        }
    }

    @Override
    public AABB getRenderBoundingBox(ProjectorBlockEntity blockEntity) {
        // The hologram can float up to MAX_DISTANCE blocks away from the block, so the vanilla unit
        // cube would cull it as soon as the projector itself leaves the frustum.
        return new AABB(blockEntity.getBlockPos()).inflate(ProjectorBlockEntity.MAX_DISTANCE + 8.0);
    }
}
