package com.brendan.holograms.client;

import com.brendan.holograms.DisplayMode;
import com.brendan.holograms.HoloSigns;
import com.brendan.holograms.ProjectorBlock;
import com.brendan.holograms.ProjectorBlockEntity;
import com.brendan.holograms.TerminalBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.state.BlockState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
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
    private static final int PANEL_FILL_ALPHA = 0xD0;
    private static final int PANEL_BORDER_ALPHA = 0xB0;
    private static final int PANEL_GLOW_ALPHA = 0x30;
    private static final int FULL_BRIGHT = 0xF000F0;
    private static final double HOLOGRAM_VIEW_DISTANCE = 64.0;
    private static final float PANEL_PADDING = 5.0F;
    private static final float PANEL_BORDER = 2.0F;
    private static final float PANEL_GLOW = 4.0F;

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
        // A hologram is emissive: the unlit panel would otherwise frame text that goes dark in
        // unlit rooms, so the glyphs use a full-bright lightmap too. The camouflage block keeps
        // the world lightmap so it still matches the surrounding blocks.
        state.blockLightCoords = state.lightCoords;
        state.lightCoords = FULL_BRIGHT;
        state.facing = blockEntity.getFacing();
        state.distance = blockEntity.getDistance();
        state.sizePercent = blockEntity.getSizePercent();
        state.backgroundColor = blockEntity.getBackgroundColor();

        BlockState camouflage = blockEntity.getCamouflage();
        BlockState renderedBlock = camouflage;
        if (renderedBlock == null && blockEntity.getBlockState().getValue(ProjectorBlock.CAMOUFLAGED)) {
            // The block flag can arrive before the block entity data, or a removed mod can leave a
            // saved camouflage unresolved. Draw the projector model instead of an invisible hole.
            renderedBlock = HoloSigns.PROJECTOR.get().defaultBlockState();
        }
        state.camouflage = camouflage;
        if (renderedBlock != null) {
            Minecraft.getInstance().getBlockModelResolver().update(
                    state.camouflageModel, renderedBlock, BlockDisplayContext.create());
            if (state.camouflageModel.isEmpty() && camouflage != null) {
                Minecraft.getInstance().getBlockModelResolver().update(
                        state.camouflageModel, HoloSigns.PROJECTOR.get().defaultBlockState(), BlockDisplayContext.create());
            }
        }
        state.hasCamouflageModel = !state.camouflageModel.isEmpty();

        state.hologramVisible = cameraPos.distanceToSqr(Vec3.atCenterOf(blockEntity.getBlockPos()))
                <= HOLOGRAM_VIEW_DISTANCE * HOLOGRAM_VIEW_DISTANCE;
        if (!state.hologramVisible) return;

        if (blockEntity.getTextVersion() != state.textVersion) {
            state.textVersion = blockEntity.getTextVersion();
            state.lines = blockEntity.getLines();
        }
        // A closed door or other solid geometry in the beam stops the projection entirely instead
        // of letting the display appear on the far side of the obstacle.
        state.obstructed = blockEntity.isProjectionObstructed();

        TerminalBuffer terminal = blockEntity.getTerminal();
        // Monitor mode wins even while the terminal is blank: falling back to the authored lines
        // here made a freshly linked computer screen show the old sign text until its first write.
        state.monitor = blockEntity.getMode() == DisplayMode.MONITOR;
        int terminalVersion = terminal.getVersion();
        if (terminalVersion != state.terminalVersion) {
            state.terminalVersion = terminalVersion;
            state.columns = terminal.getColumns();
            state.rows = terminal.getRows();
            state.monitorRows = terminal.textRows();
            state.monitorForeground = terminal.foregroundData();
        }
    }

    @Override
    public void submit(ProjectorRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                       CameraRenderState cameraState) {
        if (state.hasCamouflageModel) {
            state.camouflageModel.submitMultiLayer(
                    poseStack, collector, state.blockLightCoords, OverlayTexture.NO_OVERLAY, 0);
        }
        if (!state.hologramVisible || state.obstructed || (!state.monitor && state.lines.isEmpty())) return;

        poseStack.pushPose();
        // Holograms are billboarded with the camera orientation, exactly like name tags. This keeps
        // the text upright and readable no matter which face the projector is mounted on or where
        // the viewer stands, including straight above or below a ceiling/floor projector.
        poseStack.translate(
                0.5 + state.facing.getStepX() * (0.5 + state.distance),
                0.5 + state.facing.getStepY() * (0.5 + state.distance),
                0.5 + state.facing.getStepZ() * (0.5 + state.distance));
        poseStack.mulPose(cameraState.orientation);
        float scale = WORLD_SCALE * Math.max(0.01F, state.sizePercent / 100.0F);
        poseStack.scale(scale, -scale, scale);

        // Text is drawn one order step after the panel so the teal backing is always behind it.
        OrderedSubmitNodeCollector textCollector = collector.order(1);
        if (state.monitor) {
            submitMonitor(state, poseStack, collector, textCollector);
        } else {
            submitLines(state, poseStack, collector, textCollector);
        }
        poseStack.popPose();
    }

    private void submitLines(ProjectorRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                             OrderedSubmitNodeCollector textCollector) {
        float totalHeight = state.lines.size() * LINE_HEIGHT;
        float panelWidth = 0.0F;
        for (String line : state.lines) {
            panelWidth = Math.max(panelWidth, this.font.width(line));
        }
        if (panelWidth <= 0.0F) return;

        submitPanel(poseStack, collector, panelWidth + PANEL_PADDING * 2.0F, totalHeight + PANEL_PADDING * 2.0F,
                state.backgroundColor);
        int lineColor = isLight(state.backgroundColor) ? 0xFF063A36 : LINE_COLOR;

        for (int index = 0; index < state.lines.size(); index++) {
            String line = state.lines.get(index);
            if (line.isEmpty()) continue;
            float width = this.font.width(line);
            textCollector.submitText(
                    poseStack,
                    -width / 2.0F,
                    -totalHeight / 2.0F + index * LINE_HEIGHT,
                    Component.literal(line).getVisualOrderText(),
                    false,
                    // Depth-tested text: a wall or floor in front of the hologram hides it.
                    Font.DisplayMode.POLYGON_OFFSET,
                    state.lightCoords,
                    lineColor,
                    0,
                    0
            );
        }
    }

    private void submitMonitor(ProjectorRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                               OrderedSubmitNodeCollector textCollector) {
        int columns = state.columns;
        int rows = state.rows;
        if (columns <= 0 || rows <= 0) return;
        float totalWidth = columns * CELL_WIDTH;
        float totalHeight = rows * CELL_HEIGHT;

        submitPanel(poseStack, collector, totalWidth + PANEL_PADDING * 2.0F, totalHeight + PANEL_PADDING * 2.0F,
                state.backgroundColor);

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
                textCollector.submitText(
                        poseStack,
                        -totalWidth / 2.0F + column * CELL_WIDTH,
                        y,
                        Component.literal(String.valueOf(character)).getVisualOrderText(),
                        false,
                        Font.DisplayMode.POLYGON_OFFSET,
                        state.lightCoords,
                        0xFF000000 | color,
                        0,
                        0
                );
            }
        }
    }

    /**
     * Draws the projected teal screen behind the text. The outer quads are deliberately cull-free
     * and do not write depth: they only need to be tested against world geometry, while the text's
     * polygon offset keeps it in front of the panel.
     */
    private static void submitPanel(PoseStack poseStack, SubmitNodeCollector collector, float width, float height,
                                    int backgroundColor) {
        float halfWidth = width / 2.0F;
        float halfHeight = height / 2.0F;
        float borderHalfWidth = halfWidth + PANEL_BORDER;
        float borderHalfHeight = halfHeight + PANEL_BORDER;
        float glowHalfWidth = borderHalfWidth + PANEL_GLOW;
        float glowHalfHeight = borderHalfHeight + PANEL_GLOW;
        int rgb = backgroundColor & 0xFFFFFF;
        int tint = lighten(rgb, 0.55F);
        int glowColor = (PANEL_GLOW_ALPHA << 24) | tint;
        int borderColor = (PANEL_BORDER_ALPHA << 24) | tint;
        int fillColor = (PANEL_FILL_ALPHA << 24) | rgb;
        collector.submitCustomGeometry(poseStack, RenderTypes.debugQuads(), (pose, buffer) -> {
            // The soft outer glow may overlap the border; the border itself is four non-overlapping
            // strips so translucent upload sorting cannot wash out the dark centre behind the text.
            quad(pose, buffer, -glowHalfWidth, -glowHalfHeight, glowHalfWidth, glowHalfHeight, glowColor);
            quad(pose, buffer, -borderHalfWidth, -borderHalfHeight, borderHalfWidth, -halfHeight, borderColor);
            quad(pose, buffer, -borderHalfWidth, halfHeight, borderHalfWidth, borderHalfHeight, borderColor);
            quad(pose, buffer, -borderHalfWidth, -halfHeight, -halfWidth, halfHeight, borderColor);
            quad(pose, buffer, halfWidth, -halfHeight, borderHalfWidth, halfHeight, borderColor);
            quad(pose, buffer, -halfWidth, -halfHeight, halfWidth, halfHeight, fillColor);
        });
    }

    private static boolean isLight(int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        return 0.299 * red + 0.587 * green + 0.114 * blue > 170.0;
    }

    private static int lighten(int rgb, float amount) {
        int red = Math.round(((rgb >> 16) & 0xFF) + (255 - ((rgb >> 16) & 0xFF)) * amount);
        int green = Math.round(((rgb >> 8) & 0xFF) + (255 - ((rgb >> 8) & 0xFF)) * amount);
        int blue = Math.round((rgb & 0xFF) + (255 - (rgb & 0xFF)) * amount);
        return (red << 16) | (green << 8) | blue;
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer buffer, float minX, float minY, float maxX, float maxY, int color) {
        buffer.addVertex(pose, minX, minY, 0.0F).setColor(color);
        buffer.addVertex(pose, minX, maxY, 0.0F).setColor(color);
        buffer.addVertex(pose, maxX, maxY, 0.0F).setColor(color);
        buffer.addVertex(pose, maxX, minY, 0.0F).setColor(color);
    }

    @Override
    public boolean shouldRender(ProjectorBlockEntity blockEntity, Vec3 cameraPosition) {
        double distanceSq = Vec3.atCenterOf(blockEntity.getBlockPos()).distanceToSqr(cameraPosition);
        if (blockEntity.getBlockState().getValue(ProjectorBlock.CAMOUFLAGED)) {
            // A camouflaged projector is invisible to the chunk renderer, so its block entity must
            // keep drawing the disguise for the whole chunk view distance, not just 64 blocks.
            int chunks = Minecraft.getInstance().options.getEffectiveRenderDistance();
            double range = Math.max(HOLOGRAM_VIEW_DISTANCE, chunks * 16.0);
            return distanceSq <= range * range;
        }
        return distanceSq <= HOLOGRAM_VIEW_DISTANCE * HOLOGRAM_VIEW_DISTANCE;
    }

    @Override
    public AABB getRenderBoundingBox(ProjectorBlockEntity blockEntity) {
        // Cover the hologram and its widest panel plus the configured projection distance. A larger
        // hologram size needs a wider box so the billboard is not frustum-culled at the edges.
        double scale = Math.max(1.0, blockEntity.getSizePercent() / 100.0);
        return new AABB(blockEntity.getBlockPos())
                .inflate(blockEntity.getDistance() + 7.0 + 5.0 * (scale - 1.0));
    }
}
