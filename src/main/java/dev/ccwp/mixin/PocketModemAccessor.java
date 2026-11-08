package dev.ccwp.mixin;

import dan200.computercraft.shared.pocket.peripherals.PocketModem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** {@link PocketModem} keeps whether it is an ender modem private; turtle modems expose it as {@code advanced()}. */
@Mixin(value = PocketModem.class, remap = false)
public interface PocketModemAccessor {
    @Accessor("advanced")
    boolean ccwp$isAdvanced();
}
