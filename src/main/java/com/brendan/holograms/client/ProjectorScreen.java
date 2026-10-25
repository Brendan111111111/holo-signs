package com.brendan.holograms.client;

import com.brendan.holograms.ProjectorBlock;
import com.brendan.holograms.ProjectorBlockEntity;
import com.brendan.holograms.network.EditDisplayPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ProjectorScreen extends Screen {
    private static final int LINE_COUNT = ProjectorBlockEntity.MAX_LINES;
    private static final int CAMO_SLOT_SIZE = 20;
    private final BlockPos pos;
    private final List<String> initialLines;
    private final List<EditBox> inputs = new ArrayList<>(LINE_COUNT);
    private Direction facing;
    private int distance;
    private int sizePercent;
    private int backgroundColor;
    private @Nullable BlockState camouflage;
    private EditBox backgroundInput;
    private int distanceTextRight;
    private int distanceTextX;
    private int distanceTextY;
    private int sizeTextX;
    private int sizeTextY;
    private int backgroundLabelX;
    private int backgroundLabelY;
    private int camoLabelX;
    private int camoLabelY;
    private int camoSlotX;
    private int camoSlotY;
    private long camoErrorUntil;

    private ProjectorScreen(BlockPos pos, List<String> lines, Direction facing, int distance,
                            int sizePercent, int backgroundColor, String camouflageId) {
        super(Component.translatable("screen.holosigns.projector.title"));
        this.pos = pos;
        this.initialLines = List.copyOf(lines);
        this.facing = facing;
        this.distance = Math.max(ProjectorBlockEntity.MIN_DISTANCE, Math.min(ProjectorBlockEntity.MAX_DISTANCE, distance));
        this.sizePercent = Math.max(ProjectorBlockEntity.MIN_SIZE_PERCENT,
                Math.min(ProjectorBlockEntity.MAX_SIZE_PERCENT, sizePercent));
        this.backgroundColor = backgroundColor & 0xFFFFFF;
        this.camouflage = ProjectorBlockEntity.parseCamouflage(camouflageId);
    }

    public static void open(BlockPos pos, List<String> lines, Direction facing, int distance,
                            int sizePercent, int backgroundColor, String camouflageId) {
        Minecraft.getInstance().setScreen(new ProjectorScreen(
                pos, lines, facing, distance, sizePercent, backgroundColor, camouflageId));
    }

    @Override
    protected void init() {
        inputs.clear();
        int fieldWidth = Math.min(300, this.width - 40);
        int left = (this.width - fieldWidth) / 2;
        int saveY = this.height - 26;
        int controls2Y = saveY - 24;
        int controlsY = controls2Y - 24;
        int top = 24;
        int available = controlsY - 6 - top;
        // Keep all eight fields and both control rows on screen at the common GUI sizes.
        int spacing = Math.min(22, Math.max(10, available / LINE_COUNT));
        int fieldHeight = Math.max(8, Math.min(20, spacing - 2));
        int firstY = top + Math.max(0, (available - LINE_COUNT * spacing) / 2);

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

        sizeTextX = left;
        sizeTextY = controls2Y + 6;
        addRenderableWidget(Button.builder(Component.literal("-"), button -> changeSize(-ProjectorBlockEntity.SIZE_STEP_PERCENT))
                .bounds(left + 66, controls2Y, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+"), button -> changeSize(ProjectorBlockEntity.SIZE_STEP_PERCENT))
                .bounds(left + 88, controls2Y, 20, 20).build());

        backgroundLabelX = left + 114;
        backgroundLabelY = controls2Y + 6;
        backgroundInput = new EditBox(this.font, left + 130, controls2Y, 56, 20,
                Component.translatable("screen.holosigns.projector.background"));
        backgroundInput.setMaxLength(7);
        backgroundInput.setValue(formatColor(backgroundColor));
        addRenderableWidget(backgroundInput);

        camoSlotX = left + fieldWidth - CAMO_SLOT_SIZE;
        camoSlotY = controls2Y;
        camoLabelX = camoSlotX - 32;
        camoLabelY = controls2Y + 6;

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

    private Component sizeLabel() {
        return Component.translatable("screen.holosigns.projector.size", sizePercent + "%");
    }

    private void changeDistance(int delta) {
        distance = Math.max(ProjectorBlockEntity.MIN_DISTANCE,
                Math.min(ProjectorBlockEntity.MAX_DISTANCE, distance + delta));
        distanceTextX = distanceTextRight - this.font.width(distanceLabel());
    }

    private void changeSize(int delta) {
        sizePercent = Math.max(ProjectorBlockEntity.MIN_SIZE_PERCENT,
                Math.min(ProjectorBlockEntity.MAX_SIZE_PERCENT, sizePercent + delta));
    }

    private void pickCamouflage() {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) held = player.getOffhandItem();
        if (held.getItem() instanceof BlockItem blockItem) {
            BlockState state = blockItem.getBlock().defaultBlockState();
            if (ProjectorBlockEntity.isValidCamouflage(state)) {
                camouflage = state;
                return;
            }
        }
        camoErrorUntil = System.currentTimeMillis() + 2500L;
    }

    private void save() {
        List<String> values = inputs.stream().map(EditBox::getValue).toList();
        backgroundColor = parseColor(backgroundInput.getValue(), backgroundColor);
        EditDisplayPayload.sendSave(pos, values, facing, distance, sizePercent, backgroundColor,
                ProjectorBlockEntity.camouflageId(camouflage));
        onClose();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (isHovering(camoSlotX, camoSlotY, CAMO_SLOT_SIZE, CAMO_SLOT_SIZE, event.x(), event.y())) {
            if (event.button() == 1) {
                camouflage = null;
                return true;
            }
            if (event.button() == 0) {
                pickCamouflage();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.text(this.font, this.title, (this.width - this.font.width(this.title)) / 2, 12, 0xFFFFFFFF, true);
        graphics.text(this.font, distanceLabel(), distanceTextX, distanceTextY, 0xFFE0E0E0, true);
        graphics.text(this.font, sizeLabel(), sizeTextX, sizeTextY, 0xFFE0E0E0, true);
        graphics.text(this.font, Component.translatable("screen.holosigns.projector.background"),
                backgroundLabelX, backgroundLabelY, 0xFFE0E0E0, true);
        graphics.text(this.font, Component.translatable("screen.holosigns.projector.camo"),
                camoLabelX, camoLabelY, 0xFFE0E0E0, true);

        boolean hover = isHovering(camoSlotX, camoSlotY, CAMO_SLOT_SIZE, CAMO_SLOT_SIZE, mouseX, mouseY);
        graphics.fill(camoSlotX, camoSlotY, camoSlotX + CAMO_SLOT_SIZE, camoSlotY + CAMO_SLOT_SIZE, 0xFF8B8B8B);
        graphics.fill(camoSlotX + 1, camoSlotY + 1, camoSlotX + CAMO_SLOT_SIZE - 1,
                camoSlotY + CAMO_SLOT_SIZE - 1, 0xFF373737);
        if (hover) {
            graphics.fill(camoSlotX, camoSlotY, camoSlotX + CAMO_SLOT_SIZE,
                    camoSlotY + CAMO_SLOT_SIZE, 0x40FFFFFF);
        }
        if (camouflage != null) {
            ItemStack stack = new ItemStack(camouflage.getBlock().asItem());
            graphics.item(stack, camoSlotX + 2, camoSlotY + 2);
        } else {
            graphics.text(this.font, "+", camoSlotX + 7, camoSlotY + 6, 0xFF808080, false);
        }
        if (hover) {
            Component tooltip = System.currentTimeMillis() < camoErrorUntil
                    ? Component.translatable("screen.holosigns.projector.camo_invalid")
                    : Component.translatable("screen.holosigns.projector.camo_hint");
            graphics.setTooltipForNextFrame(tooltip, mouseX, mouseY);
        }
    }

    private static boolean isHovering(int x, int y, int width, int height, double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private static int parseColor(String value, int fallback) {
        if (value == null) return fallback;
        String hex = value.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.length() != 6) return fallback;
        try {
            return Integer.parseInt(hex, 16) & 0xFFFFFF;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String formatColor(int color) {
        return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
