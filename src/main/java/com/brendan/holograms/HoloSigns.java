package com.brendan.holograms;

import com.brendan.holograms.compat.ProjectorLinkHook;
import net.minecraft.core.BlockPos;
import com.brendan.holograms.network.EditDisplayPayload;
import com.brendan.holograms.network.TerminalInputPayload;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.util.function.Supplier;

@Mod(HoloSigns.MOD_ID)
public final class HoloSigns {
    public static final String MOD_ID = "holosigns";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    public static final BlockCapability<ProjectorBlockEntity, Direction> PROJECTOR_CAPABILITY = BlockCapability.create(
            id("projector"), ProjectorBlockEntity.class, Direction.class);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);

    private static Constructor<?> ocProjectorConstructor;
    private static boolean ocProjectorConstructorChecked;

    public static final DeferredBlock<ProjectorBlock> PROJECTOR = BLOCKS.registerBlock(
            "projector",
            ProjectorBlock::new,
            properties -> properties.mapColor(MapColor.COLOR_CYAN)
                    .strength(2.0F, 6.0F)
                    .sound(SoundType.COPPER)
                    .noOcclusion()
    );
    public static final DeferredItem<net.minecraft.world.item.BlockItem> PROJECTOR_ITEM = ITEMS.registerSimpleBlockItem("projector", PROJECTOR);
    public static final Supplier<BlockEntityType<ProjectorBlockEntity>> PROJECTOR_ENTITY = BLOCK_ENTITIES.register(
            "projector",
            () -> new BlockEntityType<>(HoloSigns::createProjectorBlockEntity, false, PROJECTOR.get())
    );
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CREATIVE_TAB = CREATIVE_TABS.register(
            "main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.holosigns.main"))
                    .withTabsBefore(CreativeModeTabs.REDSTONE_BLOCKS)
                    .icon(() -> PROJECTOR_ITEM.get().getDefaultInstance())
                    .displayItems((parameters, output) -> output.accept(PROJECTOR_ITEM.get()))
                    .build()
    );

    public HoloSigns(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
        modEventBus.addListener(EditDisplayPayload::register);
        modEventBus.addListener(TerminalInputPayload::register);
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerCapabilities);
        NeoForge.EVENT_BUS.addListener(ProjectorBlockEntity::flushPendingUpdates);
        // Registers the in-world game tests for the end-of-tick flush and projection occlusion.
        // The test instances themselves are only added when the game test server (or /test)
        // enables game tests.
        ProjectorTickFlushGameTest.register(modEventBus);
        ProjectorOcclusionGameTest.register(modEventBus);
        registerOpenComputersGameTest(modEventBus);
        // Registered unconditionally: even a build without the OpenComputers classes must keep the
        // Screen Linker from opening the projector editor.
        ProjectorLinkHook.register();
    }

    /**
     * Registers the in-world Screen Linker test when OpenComputers is installed and the optional
     * integration was compiled in. Both checks matter: the optional sources are absent from a
     * core-only build, and the test cannot place an OpenComputers case without the mod loaded.
     */
    private static void registerOpenComputersGameTest(IEventBus modEventBus) {
        if (!ModList.get().isLoaded("opencomputers")) return;
        try {
            Class.forName("com.brendan.holograms.compat.opencomputers.ProjectorOpenComputersGameTest")
                    .getMethod("register", IEventBus.class)
                    .invoke(null, modEventBus);
        } catch (ClassNotFoundException | LinkageError ignored) {
            // Core-only build, or the integration cannot resolve its OpenComputers API classes.
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not register the OpenComputers game test", e);
        }
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(PROJECTOR_CAPABILITY, PROJECTOR_ENTITY.get(), (projector, side) -> projector);
    }

    private void commonSetup(net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent event) {
        if (ModList.get().isLoaded("computercraft")) {
            event.enqueueWork(() -> {
                try {
                    // The integration lives in a separate class so loading this mod class does not
                    // resolve the CC API types when ComputerCraft is absent.
                    Class.forName("com.brendan.holograms.compat.cctweaked.CCIntegration")
                            .getMethod("init")
                            .invoke(null);
                } catch (ClassNotFoundException ignored) {
                    // A build that stripped the integration should still run the rest of the mod.
                    LOGGER.warn("ComputerCraft is installed but this build has no ComputerCraft integration; "
                            + "projectors will not expose a peripheral.");
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Could not initialise the ComputerCraft integration", e);
                }
            });
        }
        if (ModList.get().isLoaded("opencomputers")) {
            event.enqueueWork(() -> {
                try {
                    // The integration lives in an optional source set; it is present when the
                    // OpenComputers source archive was available at build time.
                    Class.forName("com.brendan.holograms.compat.opencomputers.OCIntegration")
                            .getMethod("init")
                            .invoke(null);
                } catch (ClassNotFoundException ignored) {
                    // Core-only build: OpenComputers is loaded but the optional sources were not compiled in.
                    LOGGER.warn("OpenComputers is installed but this build has no OpenComputers integration; "
                            + "the Screen Linker cannot bind the hologram projector.");
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Could not initialise the OpenComputers integration", e);
                }
            });
        }
    }

    /**
     * Creates the projector block entity, using the optional OpenComputers subclass when that mod
     * is present. The block's {@code newBlockEntity} and the registered block entity type both go
     * through this factory so every creation path gets the same class.
     */
    public static ProjectorBlockEntity createProjectorBlockEntity(BlockPos pos, BlockState state) {
        Constructor<?> constructor = ocProjectorConstructor();
        if (constructor != null) {
            try {
                return (ProjectorBlockEntity) constructor.newInstance(pos, state);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Could not construct the OpenComputers projector block entity", e);
            }
        }
        return new ProjectorBlockEntity(pos, state);
    }

    /** Looks up the optional OpenComputers block entity once instead of on every chunk load. */
    private static synchronized Constructor<?> ocProjectorConstructor() {
        if (ocProjectorConstructorChecked) return ocProjectorConstructor;
        // Do not cache the negative result when OpenComputers is not visible yet; an early block
        // entity creation must not permanently downgrade every later projector to the plain class.
        if (!ModList.get().isLoaded("opencomputers")) return null;
        ocProjectorConstructorChecked = true;
        try {
            ocProjectorConstructor = Class.forName("com.brendan.holograms.compat.opencomputers.OCProjectorBlockEntity")
                    .getConstructor(BlockPos.class, BlockState.class);
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            // Core-only build: OpenComputers is loaded but the optional sources were not compiled in.
            LOGGER.warn("OpenComputers is installed but this build has no OpenComputers integration; "
                    + "projectors will not expose a TextBuffer component.");
        } catch (LinkageError e) {
            // The integration is present but cannot bind to this OpenComputers version. Falling back
            // keeps projectors usable as signs instead of letting block entity creation fail.
            LOGGER.error("The OpenComputers integration could not load against the installed OpenComputers "
                    + "version; projectors will not expose a TextBuffer component.", e);
        }
        return ocProjectorConstructor;
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

}
