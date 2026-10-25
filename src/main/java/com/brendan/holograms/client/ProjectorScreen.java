package com.brendan.holograms.client;

import com.brendan.holograms.ProjectorBlock;
import com.brendan.holograms.ProjectorBlockEntity;
import com.brendan.holograms.network.EditDisplayPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public final class ProjectorScreen extends Screen {
    private static final int LINE_COUNT = ProjectorBlockEntity.MAX_LINES;
    private final BlockPos pos;
    private final List<String> initialLines;
    private final List<EditBox> inputs = new ArrayList<>(LINE_COUNT);
    private Direction facing;
    private int distance;
    private int distanceTextRight;
    private int distanceTextX;
    private int distanceTextY;

    private ProjectorScreen(BlockPos pos, List<String> lines, Direction facing, int distance) {
        super(Component.translatable("screen.holosigns.projector.title"));
        this.pos = pos;
        this.initialLines = List.copyOf(lines);
        this.facing = facing;
        this.distance = Math.max(ProjectorBlockEntity.MIN_DISTANCE, Math.min(ProjectorBlockEntity.MAX_DISTANCE, distance));
    }

    public static void open(BlockPos pos, List<String> lines, Direction facing, int distance) {
        Minecraft.getInstance().setScreen(new ProjectorScreen(pos, lines, facing, distance));
    }

    @Override
    protected void init() {
        inputs.clear();
        int fieldWidth = Math.min(300, this.width - 40);
        int left = (this.width - fieldWidth) / 2;
        int saveY = this.height - 26;
        int controlsY = saveY - 24;
        int top = 26;
        // Keep all eight fields and the controls on screen even at small GUI sizes.
        int spacing = Math.min(22, Math.max(16, (controlsY - 6 - top) / LINE_COUNT));
        int fieldHeight = Math.min(20, spacing - 2);
        int firstY = top + Math.max(0, (controlsY - 6 - top - LINE_COUNT * spacing) / 2);

        for (int i = 0; i < LINE_COUNT; i++) {
            EditBox input = new EditBox(this.font, left, firstY + i * spacing, fieldWidth, fieldHeight,
                    Component.translatable("screen.holosigns.projector.line", i + 1));
            input.setMaxLength(ProjectorBlockEntity.MAX_LINE_LENGTH);
            if (i < initialLines.size()) input.setValue(initialLines.get(i));
            input.setHint(Component.translatable("screen.holosigns.projector.line", i + 1));
            addRenderableWidget(input);
            inputs.add(input);
        }

        addRenderableWidget(Button.builder(facingLabel(), button -> {
            facing = ProjectorBlock.nextDirection(facing);
            button.setMessage(facingLabel());
        }).bounds(left, controlsY, 120, 20).build());

        addRenderableWidget(Button.builder(Component.literal("-"), button -> changeDistance(-1))
                .bounds(left + fieldWidth - 52, controlsY, 24, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+"), button -> changeDistance(1))
                .bounds(left + fieldWidth - 24, controlsY, 24, 20).build());

        distanceTextRight = left + fieldWidth - 60;
        distanceTextX = distanceTextRight - this.font.width(distanceLabel());
        distanceTextY = controlsY + 6;

        addRenderableWidget(Button.builder(Component.translatable("screen.holosigns.projector.save"), button -> save())
                .bounds(left, saveY, fieldWidth, 20).build());
    }

    private Component facingLabel() {
        return Component.translatable("screen.holosigns.projector.facing",
                Component.translatable("direction.holosigns." + facing.getSerializedName()));
    }

    private Component distanceLabel() {
        return Component.translatable("screen.holosigns.projector.distance", distance);
    }

    private void changeDistance(int delta) {
        distance = Math.max(ProjectorBlockEntity.MIN_DISTANCE,
                Math.min(ProjectorBlockEntity.MAX_DISTANCE, distance + delta));
        distanceTextX = distanceTextRight - this.font.width(distanceLabel());
    }

    private void save() {
        List<String> values = inputs.stream().map(EditBox::getValue).toList();
        EditDisplayPayload.sendSave(pos, values, facing, distance);
        onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.text(this.font, this.title, (this.width - this.font.width(this.title)) / 2, 12, 0xFFFFFFFF, true);
        graphics.text(this.font, distanceLabel(), distanceTextX, distanceTextY, 0xFFE0E0E0, true);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
