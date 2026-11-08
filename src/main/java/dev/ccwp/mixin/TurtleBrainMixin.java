package dev.ccwp.mixin;

import dan200.computercraft.api.turtle.TurtleSide;
import dan200.computercraft.shared.turtle.core.TurtleBrain;
import dev.ccwp.hub.HubHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A turtle that moves into another dimension (vanilla CC can't, but other mods can call {@code teleportTo}) must
 * re-check which of its links are reachable. Ordinary moves cost one comparison per modem; there's no range check for
 * mobile hosts.
 */
@Mixin(value = TurtleBrain.class, remap = false)
public abstract class TurtleBrainMixin {
    @Inject(method = "teleportTo", at = @At("RETURN"))
    private void ccwp$teleportTo(Level world, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) return;
        var brain = (TurtleBrain) (Object) this;
        for (var side : TurtleSide.values()) {
            if (brain.getPeripheral(side) instanceof HubHolder holder) holder.ccwp$getHub().onMoved();
        }
    }
}
