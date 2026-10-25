package com.brendan.holograms;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Consumer;

/**
 * In-world game test for projection-path occlusion.
 * <p>
 * The renderer hides a hologram when {@link ProjectorBlockEntity#isProjectionObstructed()} reports
 * an obstacle, so this test drives that same check on a real server level: an empty path must be
 * clear, solid blocks must block, and a closed door must block even though its thin panel sits
 * outside the beam clip.
 */
final class ProjectorOcclusionGameTest {
    private static final Identifier TEST = HoloSigns.id("projector_occlusion");
    private static final Identifier ENVIRONMENT = HoloSigns.id("projector_occlusion");
    private static final Identifier STRUCTURE = HoloSigns.id("projector_platform");

    private static final int MAX_TICKS = 40;

    private static final DeferredRegister<Consumer<GameTestHelper>> TEST_FUNCTIONS =
            DeferredRegister.create(BuiltInRegistries.TEST_FUNCTION, HoloSigns.MOD_ID);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> OCCLUSION_TEST =
            TEST_FUNCTIONS.register("projector_occlusion", () -> ProjectorOcclusionGameTest::run);

    private ProjectorOcclusionGameTest() {
    }

    static void register(IEventBus modEventBus) {
        TEST_FUNCTIONS.register(modEventBus);
        modEventBus.addListener(ProjectorOcclusionGameTest::registerTests);
    }

    private static void registerTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment =
                event.registerEnvironment(ENVIRONMENT, new TestEnvironmentDefinition.AllOf());
        event.registerTest(TEST, new FunctionGameTestInstance(OCCLUSION_TEST.getKey(),
                new TestData<>(environment, STRUCTURE, MAX_TICKS, 0, true)));
    }

    private static void run(GameTestHelper helper) {
        BlockPos projectorPos = new BlockPos(1, 1, 1);
        helper.setBlock(projectorPos, HoloSigns.PROJECTOR.get());
        ProjectorBlockEntity projector = helper.getBlockEntity(projectorPos, ProjectorBlockEntity.class);

        // The platform is behind the projector, so nothing in front of the lens must read clear.
        helper.assertFalse(projector.isProjectionObstructed(),
                "an empty projection path should not obstruct the hologram");

        BlockPos beamPos = projectorPos.above();
        helper.setBlock(beamPos, Blocks.STONE);
        helper.assertTrue(projector.isProjectionObstructed(),
                "a solid block in the projection path should obstruct the hologram");

        helper.setBlock(beamPos, Blocks.AIR);
        helper.assertFalse(projector.isProjectionObstructed(),
                "removing the obstruction should clear the hologram");

        // A closed door is a thin panel near the block edge, so the beam clip alone can miss it;
        // the block entity checks closed doors explicitly. An open door swings out of the beam.
        BlockState closedDoor = Blocks.OAK_DOOR.defaultBlockState();
        helper.setBlock(beamPos, closedDoor);
        helper.assertTrue(projector.isProjectionObstructed(),
                "a closed door in the projection path should obstruct the hologram");

        helper.setBlock(beamPos, closedDoor.setValue(BlockStateProperties.OPEN, true));
        helper.assertFalse(projector.isProjectionObstructed(),
                "an open door should not obstruct the hologram");

        // The last block the projector scans touches the hologram plane on the projector's side,
        // so it still blocks. The open door keeps the obstruction clip active without a collider.
        BlockPos lastInRange = projectorPos.above(2);
        helper.setBlock(lastInRange, Blocks.STONE);
        helper.assertTrue(projector.isProjectionObstructed(),
                "a solid block at the end of the projection distance should obstruct the hologram");
        helper.setBlock(lastInRange, Blocks.AIR);

        // The hologram plane is the far face of that last scanned block. A block mounted on the
        // far side of the plane is flush with the display, so the clip must stop short of it.
        BlockPos flushPos = projectorPos.above(3);
        helper.setBlock(flushPos, Blocks.STONE);
        helper.assertFalse(projector.isProjectionObstructed(),
                "a block flush against the display plane should not obstruct the hologram");

        // Past the display plane the projection has ended, so the block is out of range.
        BlockPos beyondPos = projectorPos.above(4);
        helper.setBlock(flushPos, Blocks.AIR);
        helper.setBlock(beyondPos, Blocks.STONE);
        helper.assertFalse(projector.isProjectionObstructed(),
                "a block beyond the projection distance should not obstruct the hologram");

        helper.setBlock(beyondPos, Blocks.AIR);
        helper.setBlock(beamPos, Blocks.AIR);

        // Every mounting direction must resolve obstruction along its own beam: ceiling (down),
        // floor (up) and the four walls. The projector is recreated for each facing so its block
        // state and block entity start clean.
        for (Direction facing : Direction.values()) {
            helper.setBlock(projectorPos, Blocks.AIR);
            helper.setBlock(projectorPos, HoloSigns.PROJECTOR.get().defaultBlockState()
                    .setValue(ProjectorBlock.FACING, facing));
            ProjectorBlockEntity directional = helper.getBlockEntity(projectorPos, ProjectorBlockEntity.class);
            // Only the first step matters here; the distance limits are covered above. Keeping the
            // range short also keeps every beam cell inside the shared structure for horizontal
            // and downward facings.
            directional.setDistance(1);

            BlockPos obstructionPos = projectorPos.relative(facing);
            helper.setBlock(obstructionPos, Blocks.AIR);
            helper.assertFalse(directional.isProjectionObstructed(),
                    "an empty " + facing + " projection path should not obstruct the hologram");

            helper.setBlock(obstructionPos, Blocks.STONE);
            helper.assertTrue(directional.isProjectionObstructed(),
                    "a solid block in front of a " + facing + "-facing projector should obstruct the hologram");

            helper.setBlock(obstructionPos, Blocks.AIR);
        }

        helper.succeed();
    }
}
