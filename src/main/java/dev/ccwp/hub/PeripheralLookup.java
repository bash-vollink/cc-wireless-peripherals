package dev.ccwp.hub;

import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import dan200.computercraft.impl.Peripherals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;

import javax.annotation.Nullable;
import java.util.function.BooleanSupplier;

/**
 * Peripheral lookup at an arbitrary position, mirroring CC's own NeoForge peripheral access: the peripheral
 * capability first, then CC's generic peripherals (inventories, fluid tanks, energy storage, ...).
 */
public final class PeripheralLookup {
    private PeripheralLookup() {
    }

    /** One-off lookup. Never loads chunks. */
    public static @Nullable IPeripheral find(ServerLevel level, BlockPos pos, Direction side) {
        if (!level.isLoaded(pos)) return null;
        var peripheral = level.getCapability(PeripheralCapability.get(), pos, side);
        if (peripheral != null) return peripheral;
        return Peripherals.getGenericPeripheral(level, pos, side, level.getBlockEntity(pos));
    }

    public static BlockCapabilityCache<IPeripheral, Direction> createCache(ServerLevel level, BlockPos pos, Direction side,
                                                                           BooleanSupplier isValid, Runnable onInvalidate) {
        return BlockCapabilityCache.create(PeripheralCapability.get(), level, pos, side, isValid, onInvalidate);
    }

    /** Lookup through a cache. Never loads chunks. */
    public static @Nullable IPeripheral find(BlockCapabilityCache<IPeripheral, Direction> cache) {
        var level = cache.level();
        var pos = cache.pos();
        if (!level.isLoaded(pos)) return null;
        var peripheral = cache.getCapability();
        if (peripheral != null) return peripheral;
        return Peripherals.getGenericPeripheral(level, pos, cache.context(), level.getBlockEntity(pos));
    }
}
