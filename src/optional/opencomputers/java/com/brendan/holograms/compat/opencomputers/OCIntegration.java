package com.brendan.holograms.compat.opencomputers;

import com.brendan.holograms.compat.ProjectorLinkHook;
import li.cil.oc.api.internal.Case;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Makes OpenComputers' Screen Linker work on the hologram projector.
 * <p>
 * The linker's item only recognises OpenComputers' own screen block entity, so the projector
 * cannot use it directly. This integration registers a handler with {@link ProjectorLinkHook},
 * which intercepts the click before the projector's editor opens and reproduces the linker's
 * behaviour: it reads the computer selected in the linker, moves the projector's node onto that
 * computer's network and thereby lets the graphics card bind the hologram as a {@code TextBuffer},
 * without any cable. Sneak-use with the linker removes the link again.
 */
public final class OCIntegration {
    private static final String SELECTED_X = "oc:linkSelX";
    private static final String SELECTED_Y = "oc:linkSelY";
    private static final String SELECTED_Z = "oc:linkSelZ";
    static {
        // Install as soon as the class is loaded: the handler must be available even if the setup
        // callback that normally calls init() never ran.
        ProjectorLinkHook.install(OCIntegration::onLinkerUse);
    }

    private OCIntegration() {
    }

    /** Explicit entry point used by the mod setup; the static block above does the real work. */
    public static void init() {
    }

    private static InteractionResult onLinkerUse(ItemStack held, Level level, BlockPos pos, Player player,
                                                 InteractionHand hand, BlockHitResult hitResult) {
        if (!(level.getBlockEntity(pos) instanceof OCProjectorBlockEntity projector)) {
            // Core-only build: the projector is a plain block entity, so nothing can be linked.
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            // Consume the click on both sides so the client does not fall back to using the block.
            return InteractionResult.SUCCESS;
        }

        if (player.isSecondaryUseActive()) {
            boolean wasLinked = projector.hasComputerLink();
            projector.unlinkComputer();
            if (wasLinked) {
                player.sendSystemMessage(Component.translatable("gui.opencomputers.linker.unlinked"));
            }
            return InteractionResult.SUCCESS;
        }

        BlockPos selected = selectedComputer(held);
        if (selected == null) {
            player.sendSystemMessage(Component.translatable("gui.opencomputers.linker.needcomputer"));
            return InteractionResult.SUCCESS;
        }
        if (selected.distSqr(pos) > (long) OCProjectorBlockEntity.LINK_RANGE * OCProjectorBlockEntity.LINK_RANGE) {
            player.sendSystemMessage(Component.translatable("gui.opencomputers.linker.outofrange"));
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(selected) instanceof Case computer)) {
            player.sendSystemMessage(Component.translatable("gui.opencomputers.linker.invalid"));
            return InteractionResult.SUCCESS;
        }
        Component message = projector.linkToComputer(selected, computer)
                ? Component.translatable("gui.opencomputers.linker.linked")
                : Component.translatable("gui.opencomputers.linker.invalid");
        player.sendSystemMessage(message);
        return InteractionResult.SUCCESS;
    }

    /**
     * Reads the computer selected in the Screen Linker. The linker stores the block position in the
     * item's custom data, so no OpenComputers internal class is needed.
     */
    private static BlockPos selectedComputer(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag tag = data.copyTag();
        if (!tag.contains(SELECTED_X)) return null;
        return new BlockPos(
                tag.getInt(SELECTED_X).orElse(0),
                tag.getInt(SELECTED_Y).orElse(0),
                tag.getInt(SELECTED_Z).orElse(0));
    }
}
