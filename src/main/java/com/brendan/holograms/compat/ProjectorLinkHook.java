package com.brendan.holograms.compat;

import com.brendan.holograms.ProjectorBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Diverts OpenComputers' Screen Linker away from the projector editor.
 * <p>
 * The linker is an ordinary item, so without this hook a right-click with it falls through to the
 * projector's {@code useWithoutItem} and opens the editor. The detection lives in the main jar
 * rather than in the optional integration so the click is intercepted on both sides even when the
 * integration is missing; in that case the click is left to normal block handling so the editor
 * still opens. The linking itself is supplied by {@link #install(Handler)} from the optional
 * integration, which is the only part that needs the OpenComputers API.
 */
public final class ProjectorLinkHook {
    private static final String OC_MOD_ID = "opencomputers";
    private static final String OC_INTEGRATION_CLASS = "com.brendan.holograms.compat.opencomputers.OCIntegration";
    private static final Identifier LINKER_ID = Identifier.fromNamespaceAndPath(OC_MOD_ID, "screenlinker");
    private static final String LINKER_CLASS = "ScreenLinker";

    /** Links or unlinks a projector; implemented by the optional OpenComputers integration. */
    public interface Handler {
        InteractionResult use(ItemStack stack, Level level, BlockPos pos, Player player,
                              InteractionHand hand, BlockHitResult hitResult);
    }

    private static volatile @Nullable Handler handler;
    private static boolean integrationLoadAttempted;

    private ProjectorLinkHook() {
    }

    /** Called by the optional integration as soon as its class is loaded. */
    public static void install(Handler newHandler) {
        handler = newHandler;
    }

    /**
     * Registers the click handler on the game bus. Called unconditionally from the mod
     * constructor so a linker click is intercepted on both sides no matter which integrations
     * were compiled into the jar.
     */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(ProjectorLinkHook::onRightClickBlock);
    }

    /**
     * Fires for every right-click on a block, including sneak-clicks. Sneak-clicks are the case a
     * block-level handler cannot catch: vanilla suppresses the block interaction while sneaking
     * with an item, so the Screen Linker's unlink action would otherwise be unreachable.
     */
    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (!(level.getBlockState(event.getPos()).getBlock() instanceof ProjectorBlock)) return;
        ItemStack stack = event.getItemStack();
        if (!isLinker(stack)) return;
        InteractionResult result = use(stack, level, event.getPos(), event.getEntity(), event.getHand(), event.getHitVec());
        // Do not swallow the click when no integration could handle it. Letting vanilla continue
        // opens the editor instead, which is visible feedback instead of a dead right-click.
        if (!result.consumesAction()) return;
        event.setCanceled(true);
        event.setCancellationResult(result);
    }

    /**
     * True when the stack is OpenComputers' Screen Linker. The registry id is used so no
     * OpenComputers class has to be loaded just to recognise the item; the class-name check covers
     * a renamed registry path.
     */
    public static boolean isLinker(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null || !OC_MOD_ID.equals(id.getNamespace())) return false;
        if (LINKER_ID.equals(id)) return true;
        return stack.getItem().getClass().getSimpleName().equals(LINKER_CLASS);
    }

    /**
     * Runs the installed handler, or passes when the OpenComputers integration is not available so
     * the normal block interaction (the editor) still gives the player feedback.
     */
    public static InteractionResult use(ItemStack stack, Level level, BlockPos pos, Player player,
                                        InteractionHand hand, BlockHitResult hitResult) {
        Handler current = handler != null ? handler : loadIntegration();
        if (current == null) return InteractionResult.PASS;
        return current.use(stack, level, pos, player, hand, hitResult);
    }

    /**
     * Installs the integration on first use when it was not initialised during setup. Loading the
     * class installs the handler, so this recovers from a setup callback that never ran instead of
     * leaving the linker silently dead.
     */
    private static @Nullable Handler loadIntegration() {
        if (integrationLoadAttempted || !ModList.get().isLoaded(OC_MOD_ID)) return null;
        integrationLoadAttempted = true;
        try {
            Class.forName(OC_INTEGRATION_CLASS);
        } catch (ClassNotFoundException | LinkageError ignored) {
            // Core-only build, or the integration cannot resolve its OpenComputers API classes.
        }
        return handler;
    }
}
