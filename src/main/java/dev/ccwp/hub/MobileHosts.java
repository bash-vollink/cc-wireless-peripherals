package dev.ccwp.hub;

import dan200.computercraft.api.turtle.TurtleSide;
import dan200.computercraft.shared.ModRegistry;
import dan200.computercraft.shared.peripheral.modem.wireless.WirelessModemPeripheral;
import dan200.computercraft.shared.pocket.items.PocketComputerItem;
import dan200.computercraft.shared.pocket.peripherals.PocketModem;
import dan200.computercraft.shared.pocket.peripherals.PocketModemPeripheral;
import dan200.computercraft.shared.turtle.blocks.TurtleBlockEntity;
import dan200.computercraft.shared.turtle.upgrades.TurtleModem;
import dev.ccwp.data.HostKey;
import dev.ccwp.mixin.PocketModemAccessor;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * Pocket computers and turtles as hosts. They are keyed by computer id ({@link HostKey.Mobile}), may only host with an
 * ender modem, and have no range limit within a dimension.
 */
public final class MobileHosts {
    private MobileHosts() {
    }

    /** Result of checking a would-be mobile host: the key, or a translation key explaining why it can't host. */
    public record Check(@Nullable HostKey.Mobile key, @Nullable String error) {
        static Check ok(int id) {
            return new Check(new HostKey.Mobile(id), null);
        }

        static Check fail(String error) {
            return new Check(null, error);
        }
    }

    /** Whether a modem peripheral belongs to a pocket computer or turtle (its position follows the device). */
    public static boolean isMobile(WirelessModemPeripheral modem) {
        return modem instanceof PocketModemPeripheral || modem.getClass().getNestHost() == TurtleModem.class;
    }

    public static boolean isPocketComputer(ItemStack stack) {
        return stack.getItem() instanceof PocketComputerItem;
    }

    /** Check a pocket computer item as a host. */
    public static Check checkPocket(ItemStack stack) {
        if (!isPocketComputer(stack)) return Check.fail("message.ccwirelessperipherals.not_pocket");
        var upgrade = PocketComputerItem.getUpgrade(stack);
        if (!(upgrade instanceof PocketModem modem) || !((PocketModemAccessor) modem).ccwp$isAdvanced()) {
            return Check.fail("message.ccwirelessperipherals.mobile_needs_ender");
        }
        var id = stack.get(ModRegistry.DataComponents.COMPUTER_ID.get());
        if (id == null) return Check.fail("message.ccwirelessperipherals.mobile_no_id");
        return Check.ok(id.id());
    }

    /** Check a turtle as a host. */
    public static Check checkTurtle(TurtleBlockEntity turtle) {
        var access = turtle.getAccess();
        var ender = false;
        for (var side : TurtleSide.values()) {
            if (access.getUpgrade(side) instanceof TurtleModem modem && modem.advanced()) ender = true;
        }
        if (!ender) return Check.fail("message.ccwirelessperipherals.mobile_needs_ender");
        var id = turtle.getComputerID();
        if (id < 0) return Check.fail("message.ccwirelessperipherals.mobile_no_id");
        return Check.ok(id);
    }
}
