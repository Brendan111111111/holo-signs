package com.brendan.holograms;

import com.brendan.holograms.compat.cctweaked.CCDisplayPeripheral;
import net.minecraft.core.BlockPos;
import com.brendan.holograms.network.EditDisplayPayload;
import dan200.computercraft.api.ComputerCraftAPI;
import dan200.computercraft.api.ForgeComputerCraftAPI;
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
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

@Mod(HoloSigns.MOD_ID)
public final class HoloSigns {
    public static final String MOD_ID = "holosigns";

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    public static final BlockCapability<ProjectorBlockEntity, Direction> PROJECTOR_CAPABILITY = BlockCapability.create(
            id("projector"), ProjectorBlockEntity.class, Direction.class);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);

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
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerCapabilities);
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(PROJECTOR_CAPABILITY, PROJECTOR_ENTITY.get(), (projector, side) -> projector);
    }

    private void commonSetup(net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent event) {
        if (ModList.get().isLoaded("computercraft")) {
            event.enqueueWork(() -> {
                ComputerCraftAPI.registerGenericSource(new CCDisplayPeripheral());
                ForgeComputerCraftAPI.registerGenericCapability(PROJECTOR_CAPABILITY);
            });
        }
    }

    private static ProjectorBlockEntity createProjectorBlockEntity(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        if (ModList.get().isLoaded("opencomputers")) {
            try {
                Class<?> compatType = Class.forName("com.brendan.holograms.compat.opencomputers.OCProjectorBlockEntity");
                return (ProjectorBlockEntity) compatType
                        .getConstructor(net.minecraft.core.BlockPos.class, net.minecraft.world.level.block.state.BlockState.class)
                        .newInstance(pos, state);
            } catch (ClassNotFoundException ignored) {
                // OpenComputers compatibility is an opt-in source set.
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Could not construct the OpenComputers projector block entity", e);
            }
        }
        return new ProjectorBlockEntity(pos, state);
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

}
