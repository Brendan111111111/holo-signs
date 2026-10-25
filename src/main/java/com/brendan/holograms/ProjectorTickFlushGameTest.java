package com.brendan.holograms;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Consumer;

/**
 * In-world game test for the projector's end-of-tick display flush.
 * <p>
 * The unit tests cover the pending-update queue in isolation; this test runs the projector on a
 * real {@link net.minecraft.server.level.ServerLevel}, so the update is queued and flushed by the
 * same server tick that network play uses. The test never calls the flush itself: it only checks
 * that the live tick hook has drained the projector by the following tick.
 */
final class ProjectorTickFlushGameTest {
    private static final Identifier TEST = HoloSigns.id("projector_tick_flush");
    private static final Identifier ENVIRONMENT = HoloSigns.id("projector_tick");
    private static final Identifier STRUCTURE = HoloSigns.id("projector_platform");

    /** Long enough for the two flush ticks plus setup, short enough to fail quickly if it stalls. */
    private static final int MAX_TICKS = 40;

    private static final DeferredRegister<Consumer<GameTestHelper>> TEST_FUNCTIONS =
            DeferredRegister.create(BuiltInRegistries.TEST_FUNCTION, HoloSigns.MOD_ID);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> FLUSH_TEST =
            TEST_FUNCTIONS.register("projector_tick_flush", () -> ProjectorTickFlushGameTest::run);

    private ProjectorTickFlushGameTest() {
    }

    static void register(IEventBus modEventBus) {
        TEST_FUNCTIONS.register(modEventBus);
        modEventBus.addListener(ProjectorTickFlushGameTest::registerTests);
    }

    private static void registerTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment =
                event.registerEnvironment(ENVIRONMENT, new TestEnvironmentDefinition.AllOf());
        event.registerTest(TEST, new FunctionGameTestInstance(FLUSH_TEST.getKey(),
                new TestData<>(environment, STRUCTURE, MAX_TICKS, 0, true)));
    }

    private static void run(GameTestHelper helper) {
        BlockPos relativePos = new BlockPos(1, 1, 1);
        helper.setBlock(relativePos, HoloSigns.PROJECTOR.get());
        ProjectorBlockEntity projector = helper.getBlockEntity(relativePos, ProjectorBlockEntity.class);

        // Writing to the terminal is the path a linked computer uses. The write must queue one
        // coalesced block update for the current projector before the tick ends.
        projector.getTerminal().write("flush");
        helper.assertTrue(projector.getMode() == DisplayMode.MONITOR,
                "a terminal write should switch the projector to monitor mode");
        helper.assertTrue(projector.isUpdatePending(),
                "a display change made during the tick should be pending before the tick ends");

        helper.runAtTickTime(1, () -> {
            helper.assertFalse(projector.isRemoved(),
                    "the projector should still be in the level when the flush runs");
            helper.assertFalse(projector.isUpdatePending(),
                    "the server tick should have flushed the pending display update");
            projector.setLine(0, "second flush");
            helper.assertTrue(projector.isUpdatePending(),
                    "a second display change should queue another update for the next tick");
        });
        helper.runAtTickTime(2, () -> {
            helper.assertFalse(projector.isUpdatePending(),
                    "the next server tick should have flushed the second display update");
            helper.succeed();
        });
    }
}
