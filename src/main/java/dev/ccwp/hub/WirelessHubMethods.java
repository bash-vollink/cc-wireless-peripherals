package dev.ccwp.hub;

import dan200.computercraft.api.lua.*;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.shared.peripheral.modem.wireless.WirelessModemPeripheral;
import dev.ccwp.CCWirelessPeripherals;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Adds the wired-modem "remote peripheral" methods to every wireless/ender modem. Together with the
 * {@code peripheral_hub} type (see the mixin), this makes CC's own {@code peripheral} API list and call wirelessly
 * linked peripherals exactly as it does for wired ones.
 * <p>
 * Registered as a CC {@link GenericSource}; CC attaches generic methods to any peripheral class matching the first
 * parameter's type.
 */
public final class WirelessHubMethods implements GenericSource {
    @Override
    public String id() {
        return CCWirelessPeripherals.MOD_ID + ":wireless_hub";
    }

    private static WirelessHub hub(WirelessModemPeripheral modem) {
        return ((HubHolder) modem).ccwp$getHub();
    }

    /**
     * List all wirelessly linked peripherals currently available to this computer.
     *
     * @return The names of the linked peripherals.
     */
    @LuaFunction
    public static List<String> getNamesRemote(WirelessModemPeripheral modem, IComputerAccess computer) {
        return new ArrayList<>(hub(modem).wrappers(computer).keySet());
    }

    /** Determine if a linked peripheral is available. */
    @LuaFunction
    public static boolean isPresentRemote(WirelessModemPeripheral modem, IComputerAccess computer, String name) {
        return hub(modem).wrapper(computer, name) != null;
    }

    /** Get the type(s) of a linked peripheral. */
    @LuaFunction
    public static @Nullable Object[] getTypeRemote(WirelessModemPeripheral modem, IComputerAccess computer, String name) {
        var wrapper = hub(modem).wrapper(computer, name);
        if (wrapper == null) return null;
        var extra = wrapper.additionalTypes();
        var result = new Object[1 + extra.size()];
        result[0] = wrapper.type();
        var i = 1;
        for (var type : extra) result[i++] = type;
        return result;
    }

    /** Check whether a linked peripheral is of a particular type. */
    @LuaFunction
    public static @Nullable Object[] hasTypeRemote(WirelessModemPeripheral modem, IComputerAccess computer, String name, String type) {
        var wrapper = hub(modem).wrapper(computer, name);
        return wrapper == null ? null : new Object[]{ wrapper.hasType(type) };
    }

    /** Get the methods of a linked peripheral. */
    @LuaFunction
    public static @Nullable Object[] getMethodsRemote(WirelessModemPeripheral modem, IComputerAccess computer, String name) {
        var wrapper = hub(modem).wrapper(computer, name);
        return wrapper == null ? null : new Object[]{ new ArrayList<>(wrapper.methodNames()) };
    }

    /** Call a method on a linked peripheral: {@code callRemote(name, method, ...)}. */
    @LuaFunction
    public static MethodResult callRemote(WirelessModemPeripheral modem, IComputerAccess computer, ILuaContext context, IArguments arguments) throws LuaException {
        var remoteName = arguments.getString(0);
        var methodName = arguments.getString(1);
        var wrapper = hub(modem).wrapper(computer, remoteName);
        if (wrapper == null) throw new LuaException("No peripheral: " + remoteName);
        return wrapper.call(context, methodName, arguments.drop(2));
    }

    /** Present for parity with wired modems; wireless modems never expose the local computer. */
    @LuaFunction
    public static @Nullable Object[] getNameLocal(WirelessModemPeripheral modem) {
        return null;
    }
}
