package com.brendan.holograms.client;

import com.brendan.holograms.ProjectorBlockEntity;
import com.brendan.holograms.network.EditDisplayPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public final class ProjectorScreen extends Screen {
    private static final int LINE_COUNT = ProjectorBlockEntity.MAX_LINES;
    private final BlockPos pos;
    private final List<String> initialLines;
    private final List<EditBox> inputs = new ArrayList<>(LINE_COUNT);

    private ProjectorScreen(BlockPos pos, List<String> lines) {
        super(Component.translatable("screen.holosigns.projector.title"));
        this.pos = pos;
        this.initialLines = List.copyOf(lines);
    }

    public static void open(BlockPos pos, List<String> lines) {
        Minecraft.getInstance().setScreen(new ProjectorScreen(pos, lines));
    }

    @Override
    protected void init() {
        inputs.clear();
        int fieldWidth = Math.min(300, this.width - 40);
        int left = (this.width - fieldWidth) / 2;
        int buttonY = this.height - 26;
        int top = 30;
        // Keep all eight fields and the save button on screen even at small GUI sizes.
        int spacing = Math.min(26, Math.max(18, (buttonY - 4 - top) / LINE_COUNT));
        int fieldHeight = Math.min(20, spacing - 3);
        int firstY = top + Math.max(0, (buttonY - 4 - top - LINE_COUNT * spacing) / 2);

        for (int i = 0; i < LINE_COUNT; i++) {
            EditBox input = new EditBox(this.font, left, firstY + i * spacing, fieldWidth, fieldHeight,
                    Component.translatable("screen.holosigns.projector.line", i + 1));
            input.setMaxLength(ProjectorBlockEntity.MAX_LINE_LENGTH);
            if (i < initialLines.size()) input.setValue(initialLines.get(i));
            input.setHint(Component.translatable("screen.holosigns.projector.line", i + 1));
            addRenderableWidget(input);
            inputs.add(input);
        }

        addRenderableWidget(Button.builder(Component.translatable("screen.holosigns.projector.save"), button -> save())
                .bounds(left, buttonY, fieldWidth, 20).build());
    }

    private void save() {
        List<String> values = inputs.stream().map(EditBox::getValue).toList();
        EditDisplayPayload.sendSave(pos, values);
        onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.text(this.font, this.title, (this.width - this.font.width(this.title)) / 2, 16, 0xFFFFFFFF, true);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
