package dev.ccwp.data;

import com.mojang.serialization.Codec;
import dev.ccwp.CCWirelessPeripherals;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Consumer;

/**
 * Server-wide storage of every wireless link, keyed by host. Only accessed from the server thread.
 */
public final class LinkSavedData extends SavedData {
    private static final String ID = CCWirelessPeripherals.MOD_ID + "_links";

    private record Entry(HostKey host, List<Link> links) {
        static final Codec<Entry> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.create(i -> i.group(
            HostKey.CODEC.fieldOf("host").forGetter(Entry::host),
            Link.CODEC.listOf().fieldOf("links").forGetter(Entry::links)
        ).apply(i, Entry::new));
    }

    private static final Codec<List<Entry>> ENTRIES_CODEC = Entry.CODEC.listOf();

    private final Map<HostKey, List<Link>> hosts = new LinkedHashMap<>();
    /** dimension -> chunk (of host) -> hosts in that chunk. */
    private final Map<ResourceKey<Level>, Long2ObjectMap<Set<HostKey>>> chunkIndex = new HashMap<>();
    /** dimension -> peripheral position -> hosts linking to it (for removing links when a peripheral is broken). */
    private final Map<ResourceKey<Level>, Map<BlockPos, Set<HostKey>>> targetIndex = new HashMap<>();
    /** Incremented on every change; cheap change detection for the client sync. */
    private int revision;

    public static LinkSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(LinkSavedData::new, LinkSavedData::load, null), ID);
    }

    private static LinkSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new LinkSavedData();
        ENTRIES_CODEC.parse(NbtOps.INSTANCE, tag.get("hosts"))
            .resultOrPartial(err -> CCWirelessPeripherals.LOG.error("Failed to read wireless links: {}", err))
            .ifPresent(entries -> {
                for (var entry : entries) {
                    if (entry.links().isEmpty()) continue;
                    data.hosts.put(entry.host(), new ArrayList<>(entry.links()));
                    data.index(entry.host());
                    for (var link : entry.links()) data.indexTarget(entry.host(), link.pos());
                }
            });
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var entries = new ArrayList<Entry>(hosts.size());
        hosts.forEach((k, v) -> entries.add(new Entry(k, List.copyOf(v))));
        ENTRIES_CODEC.encodeStart(NbtOps.INSTANCE, entries)
            .resultOrPartial(err -> CCWirelessPeripherals.LOG.error("Failed to save wireless links: {}", err))
            .ifPresent(t -> tag.put("hosts", t));
        return tag;
    }

    public int revision() {
        return revision;
    }

    public List<Link> getLinks(HostKey host) {
        var links = hosts.get(host);
        return links == null ? List.of() : Collections.unmodifiableList(links);
    }

    public Set<HostKey> hosts() {
        return Collections.unmodifiableSet(hosts.keySet());
    }

    public @Nullable Link findLink(HostKey host, BlockPos pos) {
        var links = hosts.get(host);
        if (links == null) return null;
        for (var link : links) if (link.pos().equals(pos)) return link;
        return null;
    }

    public void addLink(HostKey host, Link link) {
        var list = hosts.computeIfAbsent(host, k -> new ArrayList<>());
        if (list.isEmpty()) index(host);
        list.removeIf(l -> l.pos().equals(link.pos()));
        list.add(link);
        indexTarget(host, link.pos());
        changed();
    }

    public boolean removeLink(HostKey host, BlockPos pos) {
        var list = hosts.get(host);
        if (list == null || !list.removeIf(l -> l.pos().equals(pos))) return false;
        unindexTarget(host, pos);
        if (list.isEmpty()) removeHost(host);
        else changed();
        return true;
    }

    public void replaceLink(HostKey host, Link oldLink, Link newLink) {
        var list = hosts.get(host);
        if (list == null) return;
        var idx = list.indexOf(oldLink);
        if (idx < 0) return;
        list.set(idx, newLink);
        changed();
    }

    public boolean removeHost(HostKey host) {
        var removed = hosts.remove(host);
        if (removed == null) return false;
        for (var link : removed) unindexTarget(host, link.pos());
        var dim = chunkIndex.get(host.dimension());
        if (dim != null) {
            var chunk = ChunkPos.asLong(host.pos());
            var set = dim.get(chunk);
            if (set != null) {
                set.remove(host);
                if (set.isEmpty()) dim.remove(chunk);
            }
        }
        changed();
        return true;
    }

    /** Visit hosts whose block lies within {@code chunkRadius} chunks of {@code centre}. */
    public void forHostsNear(ResourceKey<Level> dimension, BlockPos centre, int chunkRadius, Consumer<HostKey> consumer) {
        var dim = chunkIndex.get(dimension);
        if (dim == null || dim.isEmpty()) return;
        int cx = centre.getX() >> 4, cz = centre.getZ() >> 4;
        for (int x = cx - chunkRadius; x <= cx + chunkRadius; x++) {
            for (int z = cz - chunkRadius; z <= cz + chunkRadius; z++) {
                var set = dim.get(ChunkPos.asLong(x, z));
                if (set != null) set.forEach(consumer);
            }
        }
    }

    /**
     * Remove every link pointing at a peripheral position (the peripheral block was broken).
     *
     * @return The hosts that lost a link.
     */
    public Set<HostKey> removeLinksTo(ResourceKey<Level> dimension, BlockPos pos) {
        var dim = targetIndex.get(dimension);
        var linked = dim == null ? null : dim.get(pos);
        if (linked == null || linked.isEmpty()) return Set.of();
        var affected = Set.copyOf(linked);
        for (var host : affected) removeLink(host, pos);
        return affected;
    }

    private void indexTarget(HostKey host, BlockPos pos) {
        targetIndex.computeIfAbsent(host.dimension(), k -> new HashMap<>())
            .computeIfAbsent(pos.immutable(), k -> new HashSet<>(2))
            .add(host);
    }

    private void unindexTarget(HostKey host, BlockPos pos) {
        var dim = targetIndex.get(host.dimension());
        if (dim == null) return;
        var set = dim.get(pos);
        if (set == null) return;
        set.remove(host);
        if (set.isEmpty()) dim.remove(pos);
    }

    private void index(HostKey host) {
        chunkIndex.computeIfAbsent(host.dimension(), k -> new Long2ObjectOpenHashMap<>())
            .computeIfAbsent(ChunkPos.asLong(host.pos()), k -> new HashSet<>())
            .add(host);
    }

    private void changed() {
        revision++;
        setDirty();
    }
}
