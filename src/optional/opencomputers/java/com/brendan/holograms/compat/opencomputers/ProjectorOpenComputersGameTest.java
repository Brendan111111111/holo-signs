package com.brendan.holograms.compat.opencomputers;

import com.brendan.holograms.DisplayMode;
import com.brendan.holograms.HoloSigns;
import com.brendan.holograms.ProjectorBlockEntity;
import li.cil.oc.api.internal.Case;
import li.cil.oc.api.network.Node;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * In-world game test for the OpenComputers Screen Linker integration.
 * <p>
 * The unit-testable parts of the projector do not cover the link path: it needs a real computer
 * with a graphics card, a real projector block entity and the shared OpenComputers network. This
 * test builds that setup from the actual blocks and items, links the two with the Screen Linker,
 * and checks that the graphics card ends up bound to the projector's {@code TextBuffer}.
 */
public final class ProjectorOpenComputersGameTest {
    private static final Identifier TEST = HoloSigns.id("projector_oc_bind");
    private static final Identifier ENVIRONMENT = HoloSigns.id("projector_oc_bind");
    private static final Identifier STRUCTURE = HoloSigns.id("projector_platform");

    private static final int MAX_TICKS = 40;

    private static final DeferredRegister<Consumer<GameTestHelper>> TEST_FUNCTIONS =
            DeferredRegister.create(BuiltInRegistries.TEST_FUNCTION, HoloSigns.MOD_ID);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> BIND_TEST =
            TEST_FUNCTIONS.register("projector_oc_bind", () -> ProjectorOpenComputersGameTest::run);

    private ProjectorOpenComputersGameTest() {
    }

    /** Registered from the mod constructor only when OpenComputers is installed. */
    public static void register(IEventBus modEventBus) {
        TEST_FUNCTIONS.register(modEventBus);
        modEventBus.addListener(ProjectorOpenComputersGameTest::registerTests);
    }

    private static void registerTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment =
                event.registerEnvironment(ENVIRONMENT, new TestEnvironmentDefinition.AllOf());
        event.registerTest(TEST, new FunctionGameTestInstance(BIND_TEST.getKey(),
                new TestData<>(environment, STRUCTURE, MAX_TICKS, 0, true)));
    }

    private static void run(GameTestHelper helper) {
        BlockPos casePos = new BlockPos(1, 1, 1);
        BlockPos projectorPos = new BlockPos(2, 1, 1);
        helper.setBlock(casePos, openComputersBlock("case1"));
        helper.setBlock(projectorPos, HoloSigns.PROJECTOR.get());

        // Block entities only get their onLoad() call during the level tick, and the projector
        // creates its network node there. Insert the card and link after that tick.
        helper.runAtTickTime(1, () -> {
            BlockEntity caseEntity = helper.getBlockEntity(casePos, BlockEntity.class);
            helper.assertTrue(caseEntity instanceof Case,
                    "the OpenComputers case should provide its computer block entity");
            Case computer = (Case) caseEntity;

            ItemStack graphicsCard = new ItemStack(openComputersItem("graphicscard1"));
            computer.setItem(0, graphicsCard);
            helper.assertFalse(computer.getItem(0).isEmpty(),
                    "the tier one case should accept a graphics card in its first card slot");

            ProjectorBlockEntity projector = helper.getBlockEntity(projectorPos, ProjectorBlockEntity.class);
            helper.assertTrue(projector instanceof OCProjectorBlockEntity,
                    "the projector should use its OpenComputers TextBuffer block entity");
            OCProjectorBlockEntity ocProjector = (OCProjectorBlockEntity) projector;
            helper.assertTrue(ocProjector.node() != null && ocProjector.node().network() != null,
                    "the projector should have joined a component network after its load tick");

            Player player = helper.makeMockPlayer(GameType.CREATIVE);
            player.setItemInHand(InteractionHand.MAIN_HAND, selectedLinker(helper.absolutePos(casePos)));
            BlockPos absoluteProjector = helper.absolutePos(projectorPos);
            helper.useBlock(projectorPos, player, new BlockHitResult(
                    Vec3.atCenterOf(absoluteProjector), Direction.NORTH, absoluteProjector, true));

            helper.assertTrue(ocProjector.hasComputerLink(),
                    "using the Screen Linker on the projector should link it to the computer");
            helper.assertTrue(ocProjector.getMode() == DisplayMode.MONITOR,
                    "linking a computer should switch the hologram to monitor mode");
            // The client only ever sees this update tag, not the server block entity. A blank
            // terminal used to fall back to the authored lines on screen, so make sure the
            // monitor mode is actually part of the data sent to clients.
            CompoundTag updateTag = ocProjector.getUpdateTag(helper.getLevel().registryAccess());
            helper.assertTrue("MONITOR".equals(updateTag.getStringOr("mode", "")),
                    "the block update tag should carry monitor mode to the client");

            Object graphicsCardEnvironment = findGraphicsCard(computer);
            helper.assertTrue(graphicsCardEnvironment != null,
                    "the graphics card should be installed on the computer's network");
            helper.assertTrue(boundBuffer(graphicsCardEnvironment) == ocProjector,
                    "the graphics card should bind the linked projector as its screen");

            helper.succeed();
        });
    }

    /** A Screen Linker that has the computer selected, as after right-clicking the case with it. */
    private static ItemStack selectedLinker(BlockPos computerPos) {
        ItemStack linker = new ItemStack(openComputersItem("screenlinker"));
        CompoundTag selection = new CompoundTag();
        selection.putInt("oc:linkSelX", computerPos.getX());
        selection.putInt("oc:linkSelY", computerPos.getY());
        selection.putInt("oc:linkSelZ", computerPos.getZ());
        linker.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, CustomData.of(selection));
        return linker;
    }

    private static BlockState openComputersBlock(String path) {
        Identifier id = Identifier.fromNamespaceAndPath("opencomputers", path);
        Block block = BuiltInRegistries.BLOCK.getOptional(id)
                .orElseThrow(() -> new IllegalStateException("Missing OpenComputers block " + id));
        return block.defaultBlockState();
    }

    private static Item openComputersItem(String path) {
        Identifier id = Identifier.fromNamespaceAndPath("opencomputers", path);
        return BuiltInRegistries.ITEM.getOptional(id)
                .orElseThrow(() -> new IllegalStateException("Missing OpenComputers item " + id));
    }

    /** Finds the graphics card environment among the nodes on the computer's own network. */
    private static @Nullable Object findGraphicsCard(Case computer) {
        Node root = computer.node();
        if (root == null || root.network() == null) return null;
        for (Node node : root.network().nodes()) {
            if (node.host() != null && node.host().getClass().getName().contains("GraphicsCard")) {
                return node.host();
            }
        }
        return null;
    }

    /**
     * Reads the screen a graphics card is bound to. {@code boundBuffer()} is a public method on
     * OpenComputers' graphics card class, but that class is not part of the API the mod compiles
     * against, so the test reads it reflectively instead of linking against an internal class.
     */
    private static @Nullable Object boundBuffer(Object graphicsCard) {
        try {
            Method boundBuffer = graphicsCard.getClass().getMethod("boundBuffer");
            return boundBuffer.invoke(graphicsCard);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not read the graphics card's bound screen", e);
        }
    }
}
