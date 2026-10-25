package com.brendan.holograms.compat.cctweaked;

import com.brendan.holograms.HoloSigns;
import dan200.computercraft.api.ComputerCraftAPI;
import dan200.computercraft.api.ForgeComputerCraftAPI;

/**
 * ComputerCraft setup, kept in its own class so the core mod never references the CC API.
 * <p>
 * Loading {@link CCDisplayPeripheral} resolves {@code GenericPeripheral} and its parent
 * {@code GenericSource}. If this code lived directly in {@link HoloSigns}, the JVM would verify
 * that method when the mod class loads and fail with {@code NoClassDefFoundError} on a game
 * without ComputerCraft installed. {@link HoloSigns} reaches this class reflectively only after
 * checking that the {@code computercraft} mod is loaded.
 */
public final class CCIntegration {
    private CCIntegration() {
    }

    public static void init() {
        ComputerCraftAPI.registerGenericSource(new CCDisplayPeripheral());
        ForgeComputerCraftAPI.registerGenericCapability(HoloSigns.PROJECTOR_CAPABILITY);
    }
}
