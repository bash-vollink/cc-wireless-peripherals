package dev.ccwp.hub;

import dev.ccwp.CCWirelessPeripherals;
import dev.ccwp.Config;
import dev.ccwp.data.HostKey;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Event-driven work queue for {@link WirelessHub}s. Hubs are queued when something relevant changes (a computer
 * attaches/detaches, a link is edited, a linked block changes/loads/unloads, config reload) and processed on the server
 * tick under a lookup budget. When nothing changes, the tick handler does no work at all.
 */
public final class HubScheduler {
    private static final ConcurrentLinkedQueue<WirelessHub> DIRTY = new ConcurrentLinkedQueue<>();
    /** Active hubs (with at least one computer) by host. Server thread only. */
    private static final Map<HostKey, Set<WirelessHub>> BY_HOST = new HashMap<>();

    private HubScheduler() {
    }

    public static void markDirty(WirelessHub hub) {
        if (hub.queued.compareAndSet(false, true)) DIRTY.add(hub);
    }

    /** Queue every active hub serving the given host (after links were edited). Server thread only. */
    public static void markHostDirty(HostKey host) {
        var hubs = BY_HOST.get(host);
        if (hubs != null) for (var hub : hubs) markDirty(hub);
    }

    /** Queue every active hub (after a config or blacklist reload). Server thread only. */
    public static void markAllDirty() {
        for (var hubs : BY_HOST.values()) for (var hub : hubs) markDirty(hub);
    }

    public static void tick(MinecraftServer server) {
        if (DIRTY.isEmpty()) return;
        var budget = Config.MAX_RESOLVES_PER_TICK.get();
        WirelessHub hub;
        while (budget > 0 && (hub = DIRTY.poll()) != null) {
            hub.queued.set(false);
            try {
                budget -= Math.max(1, hub.refresh(server));
            } catch (RuntimeException e) {
                CCWirelessPeripherals.LOG.error("Error refreshing wireless hub", e);
            }
        }
    }

    static void register(WirelessHub hub, HostKey key) {
        BY_HOST.computeIfAbsent(key, k -> new HashSet<>(2)).add(hub);
    }

    static void unregister(WirelessHub hub, HostKey key) {
        var hubs = BY_HOST.get(key);
        if (hubs != null && hubs.remove(hub) && hubs.isEmpty()) BY_HOST.remove(key);
    }

    /** Link status for the client view. Server thread only. */
    public static LinkStatus status(HostKey host, BlockPos pos) {
        var hubs = BY_HOST.get(host);
        if (hubs == null || hubs.isEmpty()) return LinkStatus.IDLE;
        for (var hub : hubs) if (hub.isConnected(pos)) return LinkStatus.CONNECTED;
        return LinkStatus.MISSING;
    }

    /** Number of hubs waiting to be refreshed (for tests/diagnostics). */
    public static int pendingCount() {
        return DIRTY.size();
    }

    /** Drop all state when the server stops. */
    public static void clear() {
        DIRTY.clear();
        BY_HOST.clear();
    }

    public enum LinkStatus {
        /** A computer is using the host and the peripheral is attached. */
        CONNECTED,
        /** A computer is using the host but the peripheral is missing, unloaded, out of range or blacklisted. */
        MISSING,
        /** No computer is currently using the host's modem, so links are dormant. */
        IDLE
    }
}
