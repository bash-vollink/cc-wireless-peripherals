package dev.ccwp.mixin;

import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.shared.peripheral.modem.wireless.WirelessModemPeripheral;
import dev.ccwp.hub.HubHolder;
import dev.ccwp.hub.WirelessHub;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Set;

@Mixin(value = WirelessModemPeripheral.class, remap = false)
public abstract class WirelessModemPeripheralMixin implements HubHolder, IPeripheral {
    @Unique
    private static final Set<String> CCWP$TYPES = Set.of("peripheral_hub");

    @Unique
    private volatile WirelessHub ccwp$hub;

    @Override
    public WirelessHub ccwp$getHub() {
        var hub = ccwp$hub;
        if (hub != null) return hub;
        synchronized (this) {
            if (ccwp$hub == null) ccwp$hub = new WirelessHub((WirelessModemPeripheral) (Object) this);
            return ccwp$hub;
        }
    }

    /**
     * Advertise the {@code peripheral_hub} type so CC's {@code peripheral} API looks for remote peripherals on this
     * modem, exactly as it does for wired modems. Types are read once when a computer attaches, so this is constant;
     * modems without links simply report no remote peripherals.
     */
    @Override
    public Set<String> getAdditionalTypes() {
        return CCWP$TYPES;
    }
}
