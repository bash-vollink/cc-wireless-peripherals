package dev.ccwp.mixin;

import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.shared.peripheral.modem.ModemPeripheral;
import dev.ccwp.hub.HubHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Forwards computer attach/detach on wireless modems to their {@link dev.ccwp.hub.WirelessHub}. */
@Mixin(value = ModemPeripheral.class, remap = false)
public abstract class ModemPeripheralMixin {
    @Inject(method = "attach", at = @At("TAIL"))
    private void ccwp$attach(IComputerAccess computer, CallbackInfo ci) {
        if ((Object) this instanceof HubHolder holder) holder.ccwp$getHub().attachComputer(computer);
    }

    @Inject(method = "detach", at = @At("HEAD"))
    private void ccwp$detach(IComputerAccess computer, CallbackInfo ci) {
        if ((Object) this instanceof HubHolder holder) holder.ccwp$getHub().detachComputer(computer);
    }

    @Inject(method = "removed", at = @At("TAIL"))
    private void ccwp$removed(CallbackInfo ci) {
        if ((Object) this instanceof HubHolder holder) holder.ccwp$getHub().onRemoved();
    }
}
