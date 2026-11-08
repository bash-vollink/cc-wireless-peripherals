package dev.ccwp.net;

import dev.ccwp.Blacklists;
import dev.ccwp.ModRegistry;
import dev.ccwp.data.HostKey;
import dev.ccwp.data.LinkSavedData;
import dev.ccwp.hub.HubScheduler;
import dev.ccwp.item.PeripheralLinkerItem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

/**
 * Sends link views to players holding a Peripheral Linker. Costs nothing when nobody holds one; otherwise runs every
 * {@link #INTERVAL} ticks, reads hosts from a chunk index, and only sends a packet when the view changed.
 */
public final class LinkViewSync {
    private static final int INTERVAL = 10;
    /** Hosts within this many chunks of the player are sent; the client filters further by its render distance. */
    private static final int CHUNK_RADIUS = 8;

    private static final Map<UUID, LinkViewPayload> LAST_SENT = new HashMap<>();
    private static int ticks;

    private LinkViewSync() {
    }

    public static void tick(MinecraftServer server) {
        if (++ticks % INTERVAL != 0) return;

        var players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            LAST_SENT.clear();
            return;
        }

        for (var player : players) {
            var holding = PeripheralLinkerItem.isLinker(player.getMainHandItem()) || PeripheralLinkerItem.isLinker(player.getOffhandItem());
            if (!holding) {
                var last = LAST_SENT.remove(player.getUUID());
                if (last != null && !last.hosts().isEmpty()) PacketDistributor.sendToPlayer(player, LinkViewPayload.EMPTY);
                continue;
            }

            var payload = build(server, player);
            if (!payload.equals(LAST_SENT.get(player.getUUID()))) {
                PacketDistributor.sendToPlayer(player, payload);
                LAST_SENT.put(player.getUUID(), payload);
            }
        }

        if (LAST_SENT.size() > players.size()) {
            var online = new HashSet<UUID>();
            for (var p : players) online.add(p.getUUID());
            LAST_SENT.keySet().retainAll(online);
        }
    }

    private static LinkViewPayload build(MinecraftServer server, ServerPlayer player) {
        var level = player.serverLevel();
        var data = LinkSavedData.get(server);

        var keys = new LinkedHashSet<HostKey>();
        data.forHostsNear(level.dimension(), player.blockPosition(), CHUNK_RADIUS, keys::add);
        for (var stack : List.of(player.getMainHandItem(), player.getOffhandItem())) {
            var selected = stack.get(ModRegistry.SELECTED_HOST.get());
            if (selected != null && selected.dimension().equals(level.dimension())) {
                var key = HostKey.of(selected);
                if (!data.getLinks(key).isEmpty()) keys.add(key);
            }
        }
        if (keys.isEmpty()) return LinkViewPayload.EMPTY;

        var hosts = new ArrayList<LinkViewPayload.HostView>(keys.size());
        List<HostKey> stale = null;
        for (var key : keys) {
            // Lazily prune hosts whose block is gone (explosions, pistons, /setblock...).
            if (level.isLoaded(key.pos()) && !Blacklists.isHostType(level.getBlockState(key.pos()))) {
                if (stale == null) stale = new ArrayList<>();
                stale.add(key);
                continue;
            }
            var links = data.getLinks(key);
            var views = new ArrayList<LinkViewPayload.LinkView>(links.size());
            for (var link : links) {
                views.add(new LinkViewPayload.LinkView(link.pos(), link.name(), (byte) HubScheduler.status(key, link.pos()).ordinal()));
            }
            hosts.add(new LinkViewPayload.HostView(key.pos(), List.copyOf(views)));
        }
        if (stale != null) for (var key : stale) if (data.removeHost(key)) HubScheduler.markHostDirty(key);
        return new LinkViewPayload(List.copyOf(hosts));
    }

    public static void clear() {
        LAST_SENT.clear();
    }
}
