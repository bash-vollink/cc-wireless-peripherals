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
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
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
    /** Blocks of links with a live peripheral; read by the client sync. */
    private volatile Set<GlobalPos> connected = Set.of();
    /** Dimension the modem was in at the last refresh; checked when a mobile host may have moved. */
    private volatile @Nullable ResourceKey<Level> publishedDimension;

    // ---- server thread only ----
    private @Nullable HostKey key;
    private final Map<GlobalPos, Slot> slots = new HashMap<>();

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

    boolean isConnected(GlobalPos target) {
        return connected.contains(target);
    }

    boolean hasComputers() {
        return !computers.isEmpty();
    }

    boolean isMobile() {
        return MobileHosts.isMobile(modem);
    }

    /** Where the host is now, for drawing link lines. Server thread only. */
    @Nullable
    GlobalPos currentPosition() {
        if (!(modem.getLevel() instanceof ServerLevel level)) return null;
        return GlobalPos.of(level.dimension(), BlockPos.containing(modem.getPosition()));
    }

    /**
     * A mobile host may have changed dimension (player changed dimension, turtle teleported). Only queues a refresh
     * if it actually did, so ordinary movement costs nothing.
     */
    public void onMoved() {
        var dimension = publishedDimension;
        var level = modem.getLevel();
        if (dimension != null && level != null && !level.dimension().equals(dimension)) HubScheduler.markDirty(this);
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

        var mobile = MobileHosts.isMobile(modem);
        var newKey = computeKey(level, mobile);
        if (!newKey.equals(key)) {
            release();
            key = newKey;
        }
        HubScheduler.register(this, key, mobile);
        publishedDimension = level.dimension();

        // Range: placed hosts use the config range of their block (0 = unlimited) and must be loaded and allowed to
        // host. Mobile hosts must have an ender modem and are never range-checked: only the dimension matters.
        double rangeSq = -1;
        Vec3 hostCentre = null;
        if (mobile) {
            if (!modem.isInterdimensional()) return publishEmpty();
        } else {
            var placed = (HostKey.Placed) key;
            if (!level.isLoaded(placed.pos())) return publishEmpty();
            var hostState = level.getBlockState(placed.pos());
            if (!Blacklists.isHost(hostState)) return publishEmpty();
            var range = Blacklists.rangeFor(hostState);
            if (range > 0) {
                rangeSq = (double) range * range;
                hostCentre = Vec3.atCenterOf(placed.pos());
            }
        }

        var data = LinkSavedData.get(server);
        var links = data.getLinks(key);

        // Drop slots whose link was removed or is in another dimension; add slots for new links.
        var wanted = new HashMap<GlobalPos, Link>(links.size());
        for (var link : links) if (link.dimension().equals(level.dimension())) wanted.put(link.target(), link);
        var it = slots.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (!wanted.containsKey(entry.getKey())) {
                entry.getValue().alive = false;
                it.remove();
            }
        }

        var lookups = 0;
        var oldResolved = resolved;
        var next = new LinkedHashMap<String, Resolved>();
        var nowConnected = new HashSet<GlobalPos>();
        List<GlobalPos> gone = null;

        for (var link : wanted.values()) {
            var slot = slots.get(link.target());
            if (slot == null || !slot.link.face().equals(link.face())) {
                if (slot != null) slot.alive = false;
                slot = new Slot(link);
                slots.put(link.target(), slot);
            }

            if (hostCentre != null && hostCentre.distanceToSqr(Vec3.atCenterOf(link.pos())) > rangeSq) continue;

            if (slot.cache == null) {
                var s = slot;
                // Creating a cache never loads the chunk; NeoForge fires the listener on chunk load/unload and on
                // block changes, which is what drives all further refreshes (no polling).
                slot.cache = PeripheralLookup.createCache(level, link.pos(), link.face(), () -> s.alive, () -> HubScheduler.markDirty(this));
            }
            if (!level.isLoaded(link.pos())) continue;

            lookups++;
            var peripheral = PeripheralLookup.find(slot.cache);
            if (peripheral == null) {
                // The chunk is loaded but the block no longer provides a peripheral: it was broken or replaced
                // (explosion, piston, /setblock, ...). Drop the links to it, like breaking it by hand does.
                if (gone == null) gone = new ArrayList<>();
                gone.add(link.target());
                continue;
            }

            var type = peripheral.getType();
            if (type == null || Blacklists.isPeripheralBlacklisted(level.getBlockState(link.pos()), type)) continue;

            var name = data.nameOf(link.target());
            if (name == null || !type.equals(data.typeOf(link.target()))) {
                var before = name;
                name = data.retype(link.target(), type);
                // The name is shared: other hosts linking this block must switch too.
                if (!name.equals(before)) markOtherHostsDirty(data.hostsLinking(link.target()));
            }

            var previous = oldResolved.get(name);
            next.put(name, previous != null && previous.peripheral().equals(peripheral)
                ? previous
                : new Resolved(peripheral, ServerContext.get(server).peripheralMethods().getSelfMethods(peripheral)));
            nowConnected.add(link.target());
        }

        if (gone != null) {
            for (var target : gone) {
                markOtherHostsDirty(data.removeLinksTo(target));
                var slot = slots.remove(target);
                if (slot != null) slot.alive = false;
            }
        }

        publish(next);
        connected = Set.copyOf(nowConnected);
        return lookups;
    }

    private void markOtherHostsDirty(Set<HostKey> hosts) {
        for (var host : hosts) if (!host.equals(key)) HubScheduler.markHostDirty(host);
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
        publishedDimension = null;
        if (key != null) {
            HubScheduler.unregister(this, key);
            key = null;
        }
    }

    private HostKey computeKey(ServerLevel level, boolean mobile) {
        if (mobile) {
            // A pocket computer's or turtle's modem is only ever attached to its own computer.
            return new HostKey.Mobile(computers.keySet().iterator().next().getID());
        }
        // Placed CC modems report the position of the block they are attached to, so use the block entity instead.
        // Other hosts (e.g. CC: Modern servers) report the centre of their own block.
        BlockPos pos = modem.getTarget() instanceof WirelessModemBlockEntity be
            ? be.getBlockPos()
            : BlockPos.containing(modem.getPosition());
        return new HostKey.Placed(level.dimension(), pos);
    }
}
