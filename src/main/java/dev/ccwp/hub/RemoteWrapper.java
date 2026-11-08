package dev.ccwp.hub;

import dan200.computercraft.api.filesystem.Mount;
import dan200.computercraft.api.filesystem.WritableMount;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.ILuaContext;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.NotAttachedException;
import dan200.computercraft.api.peripheral.WorkMonitor;
import dan200.computercraft.core.computer.GuardedLuaContext;
import dan200.computercraft.core.methods.PeripheralMethod;

import javax.annotation.Nullable;
import java.util.*;

/**
 * The view a linked peripheral gets of the computer using it. One instance per (computer, linked peripheral) pair.
 * Behaves like a peripheral on a wired network: the attachment name is the link name, and
 * {@code peripheral}/{@code peripheral_detach} events are queued on attach/detach.
 */
final class RemoteWrapper implements IComputerAccess, GuardedLuaContext.Guard {
    private final WirelessHub hub;
    private final IComputerAccess computer;
    private final IPeripheral peripheral;
    private final String name;
    private final String type;
    private final Set<String> additionalTypes;
    private final Map<String, PeripheralMethod> methods;
    private final Set<String> mounts = new HashSet<>();

    private volatile boolean attached;
    private @Nullable GuardedLuaContext contextWrapper;

    RemoteWrapper(WirelessHub hub, IComputerAccess computer, String name, IPeripheral peripheral, Map<String, PeripheralMethod> methods) {
        this.hub = hub;
        this.computer = computer;
        this.peripheral = peripheral;
        this.name = name;
        this.type = Objects.requireNonNull(peripheral.getType(), "Peripheral type cannot be null");
        this.additionalTypes = peripheral.getAdditionalTypes();
        this.methods = methods;
    }

    IPeripheral peripheral() {
        return peripheral;
    }

    void attach() {
        attached = true;
        peripheral.attach(this);
        computer.queueEvent("peripheral", name);
    }

    void detach() {
        try {
            peripheral.detach(this);
        } finally {
            attached = false;
            synchronized (this) {
                for (var mount : mounts) computer.unmount(mount);
                mounts.clear();
            }
            computer.queueEvent("peripheral_detach", name);
        }
    }

    String type() {
        return type;
    }

    Set<String> additionalTypes() {
        return additionalTypes;
    }

    boolean hasType(String wanted) {
        return type.equals(wanted) || additionalTypes.contains(wanted);
    }

    Collection<String> methodNames() {
        return methods.keySet();
    }

    MethodResult call(ILuaContext context, String methodName, IArguments arguments) throws LuaException {
        var method = methods.get(methodName);
        if (method == null) throw new LuaException("No such method " + methodName);

        var wrapper = contextWrapper;
        if (wrapper == null || !wrapper.wraps(context)) wrapper = contextWrapper = new GuardedLuaContext(context, this);
        return method.apply(peripheral, wrapper, this, arguments);
    }

    @Override
    public boolean checkValid() {
        return attached;
    }

    private void checkAttached() {
        if (!attached) throw new NotAttachedException();
    }

    @Override
    public synchronized @Nullable String mount(String desiredLocation, Mount mount) {
        return mount(desiredLocation, mount, name);
    }

    @Override
    public synchronized @Nullable String mount(String desiredLocation, Mount mount, String driveName) {
        checkAttached();
        var mounted = computer.mount(desiredLocation, mount, driveName);
        if (mounted != null) mounts.add(mounted);
        return mounted;
    }

    @Override
    public synchronized @Nullable String mountWritable(String desiredLocation, WritableMount mount) {
        return mountWritable(desiredLocation, mount, name);
    }

    @Override
    public synchronized @Nullable String mountWritable(String desiredLocation, WritableMount mount, String driveName) {
        checkAttached();
        var mounted = computer.mountWritable(desiredLocation, mount, driveName);
        if (mounted != null) mounts.add(mounted);
        return mounted;
    }

    @Override
    public synchronized void unmount(@Nullable String location) {
        checkAttached();
        computer.unmount(location);
        mounts.remove(location);
    }

    @Override
    public int getID() {
        checkAttached();
        return computer.getID();
    }

    @Override
    public void queueEvent(String event, @Nullable Object... arguments) {
        checkAttached();
        computer.queueEvent(event, arguments);
    }

    @Override
    public WorkMonitor getMainThreadMonitor() {
        checkAttached();
        return computer.getMainThreadMonitor();
    }

    @Override
    public String getAttachmentName() {
        checkAttached();
        return name;
    }

    @Override
    public Map<String, IPeripheral> getAvailablePeripherals() {
        checkAttached();
        return hub.peripheralsSnapshot();
    }

    @Override
    public @Nullable IPeripheral getAvailablePeripheral(String name) {
        checkAttached();
        return hub.peripheralsSnapshot().get(name);
    }
}
