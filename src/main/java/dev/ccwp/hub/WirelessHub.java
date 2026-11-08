package dev.ccwp.hub;

import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.core.methods.PeripheralMethod;
import dan200.computercraft.shared.computer.core.ServerContext;
import dan200.computercraft.shared.peripheral.modem.wireless.WirelessModemBlockEntity;
import dan200.computercraft.shared.peripheral.modem.wireless.WirelessModemPeripheral;
import dev.ccwp.Blacklists;
import dev.ccwp.data.HostKey;
import dev.ccwp.data.Link;
import dev.ccwp.data.LinkSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime state for one wireless modem peripheral: which linked peripherals are currently available, and the
 * per-computer wrappers that expose them to Lua.
 * <p>
 * Threading: {@link #attachComputer}/{@link #detachComputer} and the Lua-facing getters may be called from any thread.
 * {@link #refresh} (all world access) runs on the server thread only, driven by {@link HubScheduler}.
 */
public final class WirelessHub {
    private final WirelessModemPeripheral modem;
    final AtomicBoolean queued = new AtomicBoolean();

    private final Object lock = new Object();
    /** Mutated under {@link #lock}; read lock-free from Lua calls. */
    private final ConcurrentMap<IComputerAccess, ConcurrentMap<String, RemoteWrapper>> computers = new ConcurrentHashMap<>();
    /** Currently available peripherals (immutable snapshot, replaced under {@link #lock}). */
    private volatile Map<String, Resolved> resolved = Map.of();
    private volatile Map<String, IPeripheral> peripherals = Map.of();
    /** Positions of links with a live peripheral; read by the client sync. */
    private volatile Set<BlockPos> connected = Set.of();

    // ---- server thread only ----
    private @Nullable HostKey key;
    private final Map<BlockPos, Slot> slots = new HashMap<>();

    private record Resolved(IPeripheral peripheral, Map<String, PeripheralMethod> methods) {
    }

    private final class Slot {
        Link link;
        @Nullable BlockCapabilityCache<IPeripheral, Direction> cache;
        volatile boolean alive = true;

        Slot(Link link) {
            this.link = link;
        }
    }

    public WirelessHub(WirelessModemPeripheral modem) {
        this.modem = modem;
    }

    // ------------------------------------------------------------------
    // Computer attachment (any thread)
    // ------------------------------------------------------------------

    public void attachComputer(IComputerAccess computer) {
        synchronized (lock) {
            if (computers.containsKey(computer)) return;
            var wrappers = new ConcurrentHashMap<String, RemoteWrapper>();
            computers.put(computer, wrappers);
            for (var entry : resolved.entrySet()) attachWrapper(computer, wrappers, entry.getKey(), entry.getValue());
        }
        HubScheduler.markDirty(this);
    }

    public void detachComputer(IComputerAccess computer) {
        ConcurrentMap<String, RemoteWrapper> wrappers;
        synchronized (lock) {
            wrappers = computers.remove(computer);
            if (wrappers != null) {
                for (var wrapper : wrappers.values()) wrapper.detach();
                wrappers.clear();
            }
        }
        if (computers.isEmpty()) HubScheduler.markDirty(this);
    }

    /** The modem block entity was removed (block broken or chunk unloaded). */
    public void onRemoved() {
        HubScheduler.markDirty(this);
    }

    // ------------------------------------------------------------------
    // Lua-facing accessors (computer threads)
    // ------------------------------------------------------------------

    Map<String, RemoteWrapper> wrappers(IComputerAccess computer) {
        var wrappers = computers.get(computer);
        return wrappers == null ? Map.of() : wrappers;
    }

    @Nullable
    RemoteWrapper wrapper(IComputerAccess computer, String name) {
        var wrappers = computers.get(computer);
        return wrappers == null ? null : wrappers.get(name);
    }

    Map<String, IPeripheral> peripheralsSnapshot() {
        return peripherals;
    }

    // ------------------------------------------------------------------
    // Server-thread logic
    // ------------------------------------------------------------------

    @Nullable
    HostKey key() {
        return key;
    }

    boolean isConnected(BlockPos pos) {
        return connected.contains(pos);
    }

    boolean hasComputers() {
        return !computers.isEmpty();
    }

    /**
     * Recompute the available peripherals. Server thread only.
     *
     * @return The number of peripheral lookups performed (for the per-tick budget).
     */
    int refresh(MinecraftServer server) {
        if (computers.isEmpty() || !(modem.getLevel() instanceof ServerLevel level)) {
            release();
            return 0;
        }

        var newKey = computeKey(level);
        if (!newKey.equals(key)) {
            release();
            key = newKey;
        }
        HubScheduler.register(this, key);

        // Host block must be loaded, a known host, and not blacklisted.
        if (!level.isLoaded(key.pos())) return publishEmpty();
        var hostState = level.getBlockState(key.pos());
        if (!Blacklists.isHost(hostState)) return publishEmpty();

        var data = LinkSavedData.get(server);
        var links = data.getLinks(key);

        // Drop slots whose link was removed; add slots for new links.
        var wanted = new HashMap<BlockPos, Link>(links.size());
        for (var link : links) wanted.put(link.pos(), link);
        var it = slots.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (!wanted.containsKey(entry.getKey())) {
                entry.getValue().alive = false;
                it.remove();
            }
        }

        double range = Blacklists.rangeFor(hostState);
        double rangeSq = range * range;
        var hostCentre = Vec3.atCenterOf(key.pos());

        var lookups = 0;
        var oldResolved = resolved;
        var next = new LinkedHashMap<String, Resolved>();
        var nowConnected = new HashSet<BlockPos>();

        for (var link : links) {
            var slot = slots.get(link.pos());
            if (slot == null || !slot.link.face().equals(link.face())) {
                if (slot != null) slot.alive = false;
                slot = new Slot(link);
                slots.put(link.pos(), slot);
            } else {
                slot.link = link;
            }

            if (hostCentre.distanceToSqr(Vec3.atCenterOf(link.pos())) > rangeSq) continue;

            if (slot.cache == null) {
                var s = slot;
                // Creating a cache never loads the chunk; NeoForge fires the listener on chunk load/unload and on
                // block changes, which is what drives all further refreshes (no polling).
                slot.cache = PeripheralLookup.createCache(level, link.pos(), link.face(), () -> s.alive, () -> HubScheduler.markDirty(this));
            }
            if (!level.isLoaded(link.pos())) continue;

            lookups++;
            var peripheral = PeripheralLookup.find(slot.cache);
            if (peripheral == null) continue;

            var type = peripheral.getType();
            if (type == null || Blacklists.isPeripheralBlacklisted(level.getBlockState(link.pos()), type)) continue;

            if (!type.equals(slot.link.type())) {
                var renamed = slot.link.withName(type, nextName(server, type));
                data.replaceLink(key, slot.link, renamed);
                slot.link = renamed;
            }

            var name = slot.link.name();
            var previous = oldResolved.get(name);
            next.put(name, previous != null && previous.peripheral().equals(peripheral)
                ? previous
                : new Resolved(peripheral, ServerContext.get(server).peripheralMethods().getSelfMethods(peripheral)));
            nowConnected.add(link.pos());
        }

        publish(next);
        connected = Set.copyOf(nowConnected);
        return lookups;
    }

    private int publishEmpty() {
        publish(Map.of());
        connected = Set.of();
        return 0;
    }

    /** Swap in a new set of peripherals, detaching/attaching only what changed. */
    private void publish(Map<String, Resolved> next) {
        synchronized (lock) {
            var old = resolved;
            if (old.isEmpty() && next.isEmpty()) return;

            for (var entry : old.entrySet()) {
                if (next.get(entry.getKey()) == entry.getValue()) continue;
                for (var wrappers : computers.values()) {
                    var wrapper = wrappers.remove(entry.getKey());
                    if (wrapper != null) wrapper.detach();
                }
            }

            resolved = Map.copyOf(next);
            var flat = new HashMap<String, IPeripheral>(next.size());
            next.forEach((name, res) -> flat.put(name, res.peripheral()));
            peripherals = Map.copyOf(flat);

            for (var entry : next.entrySet()) {
                if (old.get(entry.getKey()) == entry.getValue()) continue;
                for (var computer : computers.entrySet()) {
                    attachWrapper(computer.getKey(), computer.getValue(), entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private void attachWrapper(IComputerAccess computer, ConcurrentMap<String, RemoteWrapper> wrappers, String name, Resolved res) {
        if (wrappers.containsKey(name)) return;
        var wrapper = new RemoteWrapper(this, computer, name, res.peripheral(), res.methods());
        wrappers.put(name, wrapper);
        wrapper.attach();
    }

    /** Forget all world state (caches, registration). Server thread only. */
    void release() {
        for (var slot : slots.values()) slot.alive = false;
        slots.clear();
        publish(Map.of());
        connected = Set.of();
        if (key != null) {
            HubScheduler.unregister(this, key);
            key = null;
        }
    }

    private HostKey computeKey(ServerLevel level) {
        // Placed CC modems report the position of the block they are attached to, so use the block entity instead.
        // Other hosts (e.g. CC: Modern servers) report the centre of their own block.
        BlockPos pos = modem.getTarget() instanceof WirelessModemBlockEntity be
            ? be.getBlockPos()
            : BlockPos.containing(modem.getPosition());
        return new HostKey(level.dimension(), pos);
    }

    public static String nextName(MinecraftServer server, String type) {
        return type + "_" + ServerContext.get(server).getNextId("peripheral." + type);
    }
}
