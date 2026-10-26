package com.brendan.holograms.compat.opencomputers;

import com.brendan.holograms.DisplayMode;
import com.brendan.holograms.HoloSigns;
import com.brendan.holograms.ProjectorBlockEntity;
import com.brendan.holograms.TerminalBuffer;
import com.mojang.serialization.Codec;
import li.cil.oc.api.Network;
import li.cil.oc.api.internal.Case;
import li.cil.oc.api.network.Component;
import li.cil.oc.api.network.Environment;
import li.cil.oc.api.network.Message;
import li.cil.oc.api.network.Node;
import li.cil.oc.api.network.Visibility;
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
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
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
    private static final Identifier RELINK_TEST = HoloSigns.id("projector_oc_relink");
    private static final Identifier UNLOADED_RELINK_TEST = HoloSigns.id("projector_oc_relink_unloaded");
    private static final Identifier PAINT_TEST = HoloSigns.id("projector_oc_paint");
    private static final Identifier KEYS_TEST = HoloSigns.id("projector_oc_keys");
    private static final Identifier ENVIRONMENT = HoloSigns.id("projector_oc_bind");
    private static final Identifier STRUCTURE = HoloSigns.id("projector_platform");

    private static final int MAX_TICKS = 40;
    private static final int RELINK_MAX_TICKS = 60;
    private static final int UNLOADED_RELINK_MAX_TICKS = 60;

    private static final DeferredRegister<Consumer<GameTestHelper>> TEST_FUNCTIONS =
            DeferredRegister.create(BuiltInRegistries.TEST_FUNCTION, HoloSigns.MOD_ID);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> BIND_TEST =
            TEST_FUNCTIONS.register("projector_oc_bind", () -> ProjectorOpenComputersGameTest::run);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> RELINK_TEST_FUNCTION =
            TEST_FUNCTIONS.register("projector_oc_relink", () -> ProjectorOpenComputersGameTest::runRelink);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> UNLOADED_RELINK_TEST_FUNCTION =
            TEST_FUNCTIONS.register("projector_oc_relink_unloaded",
                    () -> ProjectorOpenComputersGameTest::runUnloadedRelink);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> PAINT_TEST_FUNCTION =
            TEST_FUNCTIONS.register("projector_oc_paint", () -> ProjectorOpenComputersGameTest::runPaint);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> KEYS_TEST_FUNCTION =
            TEST_FUNCTIONS.register("projector_oc_keys", () -> ProjectorOpenComputersGameTest::runKeys);

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
        event.registerTest(RELINK_TEST, new FunctionGameTestInstance(RELINK_TEST_FUNCTION.getKey(),
                new TestData<>(environment, STRUCTURE, RELINK_MAX_TICKS, 0, true)));
        event.registerTest(UNLOADED_RELINK_TEST, new FunctionGameTestInstance(UNLOADED_RELINK_TEST_FUNCTION.getKey(),
                new TestData<>(environment, STRUCTURE, UNLOADED_RELINK_MAX_TICKS, 0, true)));
        event.registerTest(PAINT_TEST, new FunctionGameTestInstance(PAINT_TEST_FUNCTION.getKey(),
                new TestData<>(environment, STRUCTURE, MAX_TICKS, 0, true)));
        event.registerTest(KEYS_TEST, new FunctionGameTestInstance(KEYS_TEST_FUNCTION.getKey(),
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
            Object bound = boundBuffer(graphicsCardEnvironment);
            helper.assertTrue(bound != null && bound != ocProjector,
                    "the graphics card should bind the projector's screen component, not the projector component");
            helper.assertTrue(ocProjector.screenAddress() != null
                            && ocProjector.screenAddress().equals(screenAddressOf(bound)),
                    "the bound screen should be the screen component the projector advertises");

            // A graphics card binds any TextBuffer host, but an operating system looks the display
            // up by component name first. Without a real screen component OpenOS boots with no
            // display at all, which is what made the hologram look like a sign rather than a screen.
            helper.assertTrue(hasComponent(computer.node(), "screen"),
                    "the linked projector should register a screen component for the computer");
            helper.assertTrue(ocProjector.screenAddress() != null,
                    "the projector should expose the address of its screen component");
            helper.assertTrue(hasComponent(computer.node(), "keyboard"),
                    "a computer without a keyboard should get one from the linked projector");

            helper.succeed();
        });
    }

    /**
     * A bound computer must actually paint on the hologram, and the painted grid has to reach the
     * client. The graphics card writes through the same {@code TextBuffer} methods used here, so
     * this covers the path that made a linked monitor stay blank.
     */
    private static void runPaint(GameTestHelper helper) {
        BlockPos casePos = new BlockPos(1, 1, 1);
        BlockPos projectorPos = new BlockPos(2, 1, 1);
        helper.setBlock(casePos, openComputersBlock("case1"));
        helper.setBlock(projectorPos, HoloSigns.PROJECTOR.get());

        helper.runAtTickTime(1, () -> {
            Case computer = (Case) helper.getBlockEntity(casePos, BlockEntity.class);
            computer.setItem(0, new ItemStack(openComputersItem("graphicscard1")));
            OCProjectorBlockEntity projector = (OCProjectorBlockEntity) helper.getBlockEntity(
                    projectorPos, ProjectorBlockEntity.class);

            Player player = helper.makeMockPlayer(GameType.CREATIVE);
            player.setItemInHand(InteractionHand.MAIN_HAND, selectedLinker(helper.absolutePos(casePos)));
            BlockPos absoluteProjector = helper.absolutePos(projectorPos);
            helper.useBlock(projectorPos, player, new BlockHitResult(
                    Vec3.atCenterOf(absoluteProjector), Direction.NORTH, absoluteProjector, true));

            Object graphicsCard = findGraphicsCard(computer);
            helper.assertTrue(graphicsCard != null, "the graphics card should be installed");
            li.cil.oc.api.internal.TextBuffer buffer =
                    (li.cil.oc.api.internal.TextBuffer) boundBuffer(graphicsCard);
            helper.assertTrue(buffer != null && buffer != projector,
                    "the graphics card should bind the projector's screen component");
            helper.assertTrue(projector.screenAddress() != null
                            && projector.screenAddress().equals(screenAddressOf(buffer)),
                    "the bound screen should report the projector's screen address");

            // Paint a row the way the graphics card mirrors its frame to the bound screen.
            buffer.fill(0, 0, buffer.getWidth(), buffer.getHeight(), ' ');
            buffer.setForegroundColor(0xFFFFFF);
            buffer.set(1, 2, "READY", false);

            TerminalBuffer terminal = projector.getTerminal();
            helper.assertTrue(terminal.textRows().get(2).substring(1, 6).equals("READY"),
                    "text written through the TextBuffer should appear on the hologram grid");
            helper.assertTrue(terminal.foregroundAt(1, 2) == 0xFFFFFF,
                    "the written cells should keep the graphics card's foreground colour");

            // OpenComputers decodes Lua strings with the platform charset. On Windows that maps
            // the three UTF-8 bytes of a box character to three separate characters, which used to
            // arrive as three cells of mojibake. The screen must show one box cell instead.
            String byteMapped = new String(new byte[]{(byte) 0xE2, (byte) 0x94, (byte) 0x82},
                    java.nio.charset.Charset.forName("windows-1252"));
            buffer.set(1, 3, byteMapped, false);
            helper.assertTrue(terminal.characterAt(1, 3) == '\u2502',
                    "a UTF-8 box character decoded as cp1252 should become one box-drawing cell, got "
                            + (int) terminal.characterAt(1, 3));
            helper.assertTrue(terminal.characterAt(2, 3) == ' ',
                    "the byte-mapped string should not occupy three cells");

            // The client only draws from the block update tag, so the painted row must be in it.
            CompoundTag updateTag = projector.getUpdateTag(helper.getLevel().registryAccess());
            ValueInput update = TagValueInput.create(
                    ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), updateTag);
            boolean paintedRowSent = false;
            int rowIndex = 0;
            for (String row : update.childOrEmpty("terminal").listOrEmpty("rows", Codec.STRING)) {
                if (rowIndex++ == 2 && row.contains("READY")) {
                    paintedRowSent = true;
                }
            }
            helper.assertTrue(paintedRowSent,
                    "the painted row should be part of the block update sent to clients");
            helper.assertTrue(updateTag.getIntArray("linkedCase").map(link -> link.length == 3).orElse(false),
                    "the block update should tell the client the panel is linked and clickable");

            helper.assertTrue(projector.handleTerminalInput(player,
                            ProjectorBlockEntity.TerminalAction.KEY_DOWN, 'x', 45, 0.0, 0.0, 0, ""),
                    "a linked projector should accept terminal input from the hologram panel");
            helper.assertTrue(projector.handleTerminalInput(player,
                            ProjectorBlockEntity.TerminalAction.REFRESH, '\0', 0, 0.0, 0.0, 0, ""),
                    "opening the terminal should ask the linked computer to repaint");
            helper.succeed();
        });
    }

    /**
     * Typed keys have to arrive at the bound computer as its usual {@code key_down} events. A real
     * screen routes them through a built-in keyboard; the projector has none, so it must send the
     * checked signal itself. The recorder node acts as the machine on the computer's network.
     */
    private static void runKeys(GameTestHelper helper) {
        BlockPos casePos = new BlockPos(1, 1, 1);
        BlockPos projectorPos = new BlockPos(2, 1, 1);
        helper.setBlock(casePos, openComputersBlock("case1"));
        helper.setBlock(projectorPos, HoloSigns.PROJECTOR.get());

        helper.runAtTickTime(1, () -> {
            Case computer = (Case) helper.getBlockEntity(casePos, BlockEntity.class);
            OCProjectorBlockEntity projector = (OCProjectorBlockEntity) helper.getBlockEntity(
                    projectorPos, ProjectorBlockEntity.class);

            Player player = helper.makeMockPlayer(GameType.CREATIVE);
            player.setItemInHand(InteractionHand.MAIN_HAND, selectedLinker(helper.absolutePos(casePos)));
            BlockPos absoluteProjector = helper.absolutePos(projectorPos);
            helper.useBlock(projectorPos, player, new BlockHitResult(
                    Vec3.atCenterOf(absoluteProjector), Direction.NORTH, absoluteProjector, true));

            RecordingEnvironment recorder = new RecordingEnvironment();
            // A fresh node has no network, and connect() is a no-op until it has one.
            Network.joinNewNetwork(recorder.node());
            recorder.node.connect(computer.node());
            helper.assertTrue(recorder.node.network() == projector.node().network(),
                    "the recorder should share the computer's network with the projector");

            helper.assertTrue(projector.handleTerminalInput(player,
                            ProjectorBlockEntity.TerminalAction.KEY_DOWN, 'a', 30, 0.0, 0.0, 0, ""),
                    "the linked projector should accept a typed key");
            helper.assertTrue(projector.handleTerminalInput(player,
                            ProjectorBlockEntity.TerminalAction.KEY_UP, 'a', 30, 0.0, 0.0, 0, ""),
                    "the linked projector should accept a key release");

            List<Message> messages = recorder.messages;
            helper.assertTrue(messages.stream().anyMatch(message -> isKeySignal(message, "key_down", 'a', 30)),
                    "typing should raise a key_down event on the bound computer, got " + describe(messages));
            helper.assertTrue(messages.stream().anyMatch(message -> isKeySignal(message, "key_up", 'a', 30)),
                    "releasing the key should raise a key_up event on the bound computer");

            // OpenComputers' terminal drops key events whose source is not one of the keyboards the
            // screen reports, so the two have to agree.
            Object[] keyboards = projector.getKeyboards(null, null);
            helper.assertTrue(keyboards.length == 1 && keyboards[0] instanceof List<?> list && !list.isEmpty(),
                    "the hologram should report a keyboard so the terminal accepts its key events");
            Object keyboard = ((List<?>) keyboards[0]).get(0);
            helper.assertTrue(messages.stream().anyMatch(message -> isKeySignal(message, "key_down", 'a', 30)
                            && message.source() != null && keyboard.equals(message.source().address())),
                    "key events should come from a keyboard the hologram reports, got " + describe(messages));
            helper.assertTrue(isKeyboardComponent(computer.node(), keyboard),
                    "the reported key source should be a keyboard component on the computer's network");

            // The same method over the component API, which is how the terminal reads it from Lua.
            // Component.invoke converts the list to an array, i.e. a Lua table of addresses.
            Node screenNode = computer.node().network().node(projector.screenAddress());
            helper.assertTrue(screenNode instanceof Component,
                    "the projector's screen address should belong to a component on the computer's network");
            Object[] advertised = ((Component) screenNode).invoke("getKeyboards", null);
            helper.assertTrue(advertised.length == 1 && advertised[0] instanceof Object[] table
                            && table.length == 1 && keyboard.equals(table[0]),
                    "getKeyboards should expose the key source as a table over the component API, got "
                            + java.util.Arrays.toString(advertised) + " waiting for " + keyboard);
            helper.succeed();
        });
    }

    private static boolean isKeySignal(Message message, String name, char character, int code) {
        if (!"computer.checked_signal".equals(message.name())) return false;
        Object[] data = message.data();
        return data.length >= 4
                && name.equals(data[1])
                && data[2] instanceof Integer typed && typed == (int) character
                && data[3] instanceof Integer typedCode && typedCode == code;
    }

    private static String describe(List<Message> messages) {
        List<String> names = new ArrayList<>();
        for (Message message : messages) {
            names.add(message.name() + java.util.Arrays.toString(message.data()));
        }
        return names.toString();
    }

    /** A node that keeps every message delivered to it, standing in for the machine. */
    private static final class RecordingEnvironment implements Environment {
        private final List<Message> messages = new ArrayList<>();
        private final Node node = Network.newNode(this, Visibility.Network).create();

        @Override public Node node() { return node; }
        @Override public void onConnect(Node node) {}
        @Override public void onDisconnect(Node node) {}
        @Override public void onMessage(Message message) { messages.add(message); }
    }

    /**
     * Reproduces the restart case where the projector keeps its saved link while the computer is
     * unavailable. Removing and replacing the case leaves the projector's saved position intact;
     * its server tick must reconnect when the computer is loaded again, without another linker
     * click.
     */
    private static void runRelink(GameTestHelper helper) {
        BlockPos casePos = new BlockPos(1, 1, 1);
        BlockPos projectorPos = new BlockPos(2, 1, 1);
        helper.setBlock(casePos, openComputersBlock("case1"));
        helper.setBlock(projectorPos, HoloSigns.PROJECTOR.get());

        helper.runAtTickTime(1, () -> {
            BlockEntity caseEntity = helper.getBlockEntity(casePos, BlockEntity.class);
            helper.assertTrue(caseEntity instanceof Case,
                    "the OpenComputers case should provide its computer block entity");
            ProjectorBlockEntity projector = helper.getBlockEntity(projectorPos, ProjectorBlockEntity.class);
            helper.assertTrue(projector instanceof OCProjectorBlockEntity,
                    "the projector should use its OpenComputers TextBuffer block entity");
            OCProjectorBlockEntity ocProjector = (OCProjectorBlockEntity) projector;

            Player player = helper.makeMockPlayer(GameType.CREATIVE);
            player.setItemInHand(InteractionHand.MAIN_HAND, selectedLinker(helper.absolutePos(casePos)));
            BlockPos absoluteProjector = helper.absolutePos(projectorPos);
            helper.useBlock(projectorPos, player, new BlockHitResult(
                    Vec3.atCenterOf(absoluteProjector), Direction.NORTH, absoluteProjector, true));
            helper.assertTrue(ocProjector.hasComputerLink(),
                    "the Screen Linker should save the projector-to-computer link");

            // The computer loading after the projector is the restart race. Removing it here leaves
            // the projector with the same saved position but no computer to talk to.
            helper.setBlock(casePos, Blocks.AIR);
        });

        helper.runAtTickTime(5, () -> helper.setBlock(casePos, openComputersBlock("case1")));
        helper.runAtTickTime(25, () -> {
            BlockEntity caseEntity = helper.getBlockEntity(casePos, BlockEntity.class);
            helper.assertTrue(caseEntity instanceof Case,
                    "the replacement case should provide its computer block entity");
            Case computer = (Case) caseEntity;
            ProjectorBlockEntity projector = helper.getBlockEntity(projectorPos, ProjectorBlockEntity.class);
            helper.assertTrue(projector instanceof OCProjectorBlockEntity,
                    "the projector should still use its OpenComputers TextBuffer block entity");
            OCProjectorBlockEntity ocProjector = (OCProjectorBlockEntity) projector;

            helper.assertTrue(ocProjector.node() != null && computer.node() != null,
                    "the projector and computer should both have network nodes");
            helper.assertTrue(ocProjector.node().network() == computer.node().network(),
                    "the saved link should reconnect without another Screen Linker click");

            computer.setItem(0, new ItemStack(openComputersItem("graphicscard1")));
        });
        helper.runAtTickTime(30, () -> {
            Case computer = (Case) helper.getBlockEntity(casePos, BlockEntity.class);
            OCProjectorBlockEntity projector = (OCProjectorBlockEntity) helper.getBlockEntity(
                    projectorPos, ProjectorBlockEntity.class);
            Object graphicsCardEnvironment = findGraphicsCard(computer);
            helper.assertTrue(graphicsCardEnvironment != null,
                    "the graphics card should be installed on the computer's network");
            Object bound = boundBuffer(graphicsCardEnvironment);
            helper.assertTrue(bound != null && projector.screenAddress() != null
                            && projector.screenAddress().equals(screenAddressOf(bound)),
                    "the restored link should let the graphics card bind the projector's screen");
            helper.succeed();
        });
    }

    /**
     * Reproduces a chunk unload. Unloading a chunk drops the projector's block entity, and a later
     * reload recreates it from the chunk's saved data. Here the saved tag is captured, the block is
     * removed, the linked case is replaced while the projector is gone, and the tag is loaded into
     * the fresh block entity. The restored link must find the replacement case again.
     */
    private static void runUnloadedRelink(GameTestHelper helper) {
        BlockPos casePos = new BlockPos(1, 1, 1);
        BlockPos projectorPos = new BlockPos(2, 1, 1);
        CompoundTag[] savedProjector = new CompoundTag[1];
        helper.setBlock(casePos, openComputersBlock("case1"));
        helper.setBlock(projectorPos, HoloSigns.PROJECTOR.get());

        helper.runAtTickTime(1, () -> {
            BlockEntity caseEntity = helper.getBlockEntity(casePos, BlockEntity.class);
            helper.assertTrue(caseEntity instanceof Case,
                    "the OpenComputers case should provide its computer block entity");
            ProjectorBlockEntity projector = helper.getBlockEntity(projectorPos, ProjectorBlockEntity.class);
            helper.assertTrue(projector instanceof OCProjectorBlockEntity,
                    "the projector should use its OpenComputers TextBuffer block entity");
            OCProjectorBlockEntity ocProjector = (OCProjectorBlockEntity) projector;

            Player player = helper.makeMockPlayer(GameType.CREATIVE);
            player.setItemInHand(InteractionHand.MAIN_HAND, selectedLinker(helper.absolutePos(casePos)));
            BlockPos absoluteProjector = helper.absolutePos(projectorPos);
            helper.useBlock(projectorPos, player, new BlockHitResult(
                    Vec3.atCenterOf(absoluteProjector), Direction.NORTH, absoluteProjector, true));
            helper.assertTrue(ocProjector.hasComputerLink(),
                    "the Screen Linker should save the projector-to-computer link");

            // The chunk save writes this tag before the chunk unload drops the block entity.
            savedProjector[0] = ocProjector.saveWithFullMetadata(helper.getLevel().registryAccess());
            helper.assertTrue(savedProjector[0].getIntArray("linkedCase").map(link -> link.length == 3).orElse(false),
                    "the saved projector data should contain the linked case position");
            helper.setBlock(projectorPos, Blocks.AIR);
        });

        // Replace the computer while the projector's chunk is not loaded.
        helper.runAtTickTime(5, () -> {
            helper.setBlock(casePos, Blocks.AIR);
            helper.setBlock(casePos, openComputersBlock("case1"));
        });

        helper.runAtTickTime(10, () -> {
            helper.setBlock(projectorPos, HoloSigns.PROJECTOR.get());
            BlockEntity reloadedEntity = helper.getBlockEntity(projectorPos, BlockEntity.class);
            helper.assertTrue(reloadedEntity instanceof OCProjectorBlockEntity,
                    "the reloaded projector should use its OpenComputers TextBuffer block entity");
            OCProjectorBlockEntity reloaded = (OCProjectorBlockEntity) reloadedEntity;
            helper.assertFalse(reloaded.hasComputerLink(),
                    "a freshly created block entity should start without the saved link");
            reloaded.loadWithComponents(TagValueInput.create(
                    ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), savedProjector[0]));
            helper.assertTrue(reloaded.hasComputerLink(),
                    "loading the chunk should restore the projector's saved link");
            helper.assertTrue(reloaded.getMode() == DisplayMode.MONITOR,
                    "loading the chunk should restore monitor mode");
        });

        helper.runAtTickTime(20, () -> {
            Case computer = (Case) helper.getBlockEntity(casePos, BlockEntity.class);
            OCProjectorBlockEntity projector = (OCProjectorBlockEntity) helper.getBlockEntity(
                    projectorPos, ProjectorBlockEntity.class);
            helper.assertTrue(projector.node() != null && computer.node() != null,
                    "the reloaded projector and replacement case should both have network nodes");
            helper.assertTrue(projector.node().network() == computer.node().network(),
                    "the reloaded projector should rejoin the replacement case's network");
            computer.setItem(0, new ItemStack(openComputersItem("graphicscard1")));
        });
        helper.runAtTickTime(30, () -> {
            Case computer = (Case) helper.getBlockEntity(casePos, BlockEntity.class);
            OCProjectorBlockEntity projector = (OCProjectorBlockEntity) helper.getBlockEntity(
                    projectorPos, ProjectorBlockEntity.class);
            Object graphicsCardEnvironment = findGraphicsCard(computer);
            helper.assertTrue(graphicsCardEnvironment != null,
                    "the graphics card should be installed on the replacement case's network");
            Object bound = boundBuffer(graphicsCardEnvironment);
            helper.assertTrue(bound != null && projector.screenAddress() != null
                            && projector.screenAddress().equals(screenAddressOf(bound)),
                    "the reloaded link should let the graphics card bind the projector's screen");
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

    /** The node address the bound buffer reports, i.e. the screen component's address. */
    private static @Nullable String screenAddressOf(Object buffer) {
        return buffer instanceof Environment environment && environment.node() != null
                ? environment.node().address() : null;
    }

    /** True when a component with this name is reachable from the computer's node. */
    private static boolean hasComponent(Node root, String name) {
        if (root == null || root.network() == null) return false;
        for (Node node : root.network().nodes()) {
            if (node != root && node instanceof Component component && name.equals(component.name())) {
                return true;
            }
        }
        return false;
    }

    /** True when the address belongs to a {@code keyboard} component on the computer's network. */
    private static boolean isKeyboardComponent(Node root, Object address) {
        if (root == null || root.network() == null || !(address instanceof String wanted)) return false;
        for (Node node : root.network().nodes()) {
            if (node != root && node.address() != null && node.address().equals(wanted)
                    && node instanceof Component component && "keyboard".equals(component.name())) {
                return true;
            }
        }
        return false;
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
