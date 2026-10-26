package com.brendan.holograms;

import com.brendan.holograms.compat.ProjectorLinkHook;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Hologram projector. It can be mounted on any of the six faces of a block: the face it was
 * placed against becomes the projection direction, so a projector stuck to a ceiling points
 * down and one on a wall points sideways. Sneak-use cycles through all six directions.
 */
public final class ProjectorBlock extends Block implements EntityBlock {
    public static final MapCodec<ProjectorBlock> CODEC = simpleCodec(ProjectorBlock::new);
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    public static final BooleanProperty CAMOUFLAGED = BooleanProperty.create("camouflaged");

    /** Cycle order used by sneak-use and the editor's direction button. */
    public static final Direction[] DIRECTION_CYCLE = {
            Direction.UP, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.DOWN
    };

    public ProjectorBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.UP));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, CAMOUFLAGED);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        // A camouflaged projector is drawn by its block entity renderer using the chosen block's
        // model, so the chunk renderer must not draw the projector model underneath it.
        return state.getValue(CAMOUFLAGED) ? RenderShape.INVISIBLE : RenderShape.MODEL;
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        // The clicked face is the normal of the surface the projector is mounted on, so pointing
        // away from it projects out of floors, ceilings and walls alike.
        return defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(FACING, mirror.getRotation(state.getValue(FACING)).rotate(state.getValue(FACING)));
    }

    public static Direction nextDirection(Direction current) {
        for (int i = 0; i < DIRECTION_CYCLE.length; i++) {
            if (DIRECTION_CYCLE[i] == current) return DIRECTION_CYCLE[(i + 1) % DIRECTION_CYCLE.length];
        }
        return Direction.UP;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        // Delegate to the factory so an OpenComputers build can substitute its TextBuffer-capable
        // block entity. Constructing the plain class here would bypass that substitution entirely.
        return HoloSigns.createProjectorBlockEntity(pos, state);
    }

    /**
     * Gives the block entity a server tick hook. The OpenComputers integration uses it to retry a
     * saved wireless link whose computer had not loaded yet when the projector was loaded.
     */
    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return (tickLevel, pos, tickState, entity) -> {
            if (entity instanceof ProjectorBlockEntity projector) projector.serverTick();
        };
    }

    /**
     * Consumes clicks made with OpenComputers' Screen Linker. Without this the click falls through
     * to {@link #useWithoutItem} and opens the editor, which makes the projector impossible to bind
     * to a computer wirelessly. Non-linker items keep vanilla's empty-hand fallback, so the editor
     * still opens on a normal right-click.
     */
    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                          Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (ProjectorLinkHook.isLinker(stack)) {
            InteractionResult result = ProjectorLinkHook.use(stack, level, pos, player, hand, hitResult);
            if (result.consumesAction()) return result;
            // No OpenComputers integration handled the click (core-only build, or a projector
            // without the TextBuffer block entity). Fall through so the editor still opens instead
            // of leaving the right-click dead.
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hitResult);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (player.isSecondaryUseActive()) {
            if (!level.isClientSide() && level.getBlockEntity(pos) instanceof ProjectorBlockEntity projector) {
                projector.setFacing(nextDirection(state.getValue(FACING)));
            }
            return InteractionResult.SUCCESS;
        }
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof ProjectorBlockEntity projector) {
            projector.openEditor(serverPlayer);
        }
        return InteractionResult.SUCCESS;
    }
}
