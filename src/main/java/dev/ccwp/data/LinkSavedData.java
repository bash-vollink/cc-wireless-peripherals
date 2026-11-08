package dev.ccwp.data;

import com.mojang.serialization.Codec;
import dev.ccwp.CCWirelessPeripherals;
import dev.ccwp.Config;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
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
 * Server-wide storage of every wireless link, keyed by host, plus the naming state (see {@link LinkNames}). Only
 * accessed from the server thread.
 * <p>
 * Names are unique across all links. When a block's last link goes away its name is remembered for that block, so
 * linking the block again (to any host) gives it the same name. Automatic names that are no longer used go into a
 * per-type pool and, if {@link Config#REUSE_FREED_NAMES} is on, are handed out again before the counter grows; a
 * name taken from the pool is no longer remembered for its old block.
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

    /** A name remembered for a peripheral block whose links were all removed. */
    private record Remembered(GlobalPos block, String type, String name, boolean custom) {
        static final Codec<Remembered> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.create(i -> i.group(
            GlobalPos.CODEC.fieldOf("block").forGetter(Remembered::block),
            Codec.STRING.fieldOf("type").forGetter(Remembered::type),
            Codec.STRING.fieldOf("name").forGetter(Remembered::name),
            Codec.BOOL.optionalFieldOf("custom", false).forGetter(Remembered::custom)
        ).apply(i, Remembered::new));
    }

    private static final Codec<Map<String, Integer>> NEXT_IDS_CODEC = Codec.unboundedMap(Codec.STRING, Codec.INT);
    private static final Codec<Map<String, List<Integer>>> FREE_IDS_CODEC = Codec.unboundedMap(Codec.STRING, Codec.INT.listOf());
    private static final Codec<List<Remembered>> REMEMBERED_CODEC = Remembered.CODEC.listOf();

    /** Oldest remembered names are forgotten beyond this many. */
    static final int MAX_REMEMBERED = 4096;

    /** A link together with the host that owns it. */
    public record HostLink(HostKey host, Link link) {
    }

    /** Outcome of {@link #rename}: the updated link, or a translation key explaining why the name was refused. */
    public record RenameResult(@Nullable Link link, @Nullable String error) {
    }

    private final Map<HostKey, List<Link>> hosts = new LinkedHashMap<>();
    /** dimension -> chunk (of host) -> hosts in that chunk. */
    private final Map<ResourceKey<Level>, Long2ObjectMap<Set<HostKey>>> chunkIndex = new HashMap<>();
    /** dimension -> peripheral position -> hosts linking to it (for removing links when a peripheral is broken). */
    private final Map<ResourceKey<Level>, Map<BlockPos, Set<HostKey>>> targetIndex = new HashMap<>();
    /** Incremented on every change; cheap change detection for the client sync. */
    private int revision;

    // ---- naming ----
    /** type -> next number for automatic names. */
    private final Map<String, Integer> nextIds = new HashMap<>();
    /** type -> automatic name numbers no longer used by any link. */
    private final Map<String, TreeSet<Integer>> freeIds = new HashMap<>();
    /** Insertion-ordered (oldest first), so the oldest entries are dropped once over {@link #MAX_REMEMBERED}. */
    private final LinkedHashMap<GlobalPos, Remembered> remembered = new LinkedHashMap<>();
    private final Map<String, GlobalPos> rememberedByName = new HashMap<>();
    /** name -> number of links using it (1 unless an old save had duplicates). */
    private final Map<String, Integer> liveNames = new HashMap<>();

    public static LinkSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(LinkSavedData::new, LinkSavedData::load, null), ID);
    }

    /** Public for tests; normally only reached through {@link #get}. */
    public static LinkSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new LinkSavedData();
        ENTRIES_CODEC.parse(NbtOps.INSTANCE, tag.get("hosts"))
            .resultOrPartial(err -> CCWirelessPeripherals.LOG.error("Failed to read wireless links: {}", err))
            .ifPresent(entries -> {
                for (var entry : entries) {
                    if (entry.links().isEmpty()) continue;
                    data.hosts.put(entry.host(), new ArrayList<>(entry.links()));
                    data.index(entry.host());
                    for (var link : entry.links()) {
                        data.indexTarget(entry.host(), link.pos());
                        data.liveNames.merge(link.name(), 1, Integer::sum);
                    }
                }
            });
        if (tag.contains("next_ids")) {
            NEXT_IDS_CODEC.parse(NbtOps.INSTANCE, tag.get("next_ids"))
                .resultOrPartial(err -> CCWirelessPeripherals.LOG.error("Failed to read wireless link name counters: {}", err))
                .ifPresent(data.nextIds::putAll);
        }
        if (tag.contains("free_ids")) {
            FREE_IDS_CODEC.parse(NbtOps.INSTANCE, tag.get("free_ids"))
                .resultOrPartial(err -> CCWirelessPeripherals.LOG.error("Failed to read freed wireless link names: {}", err))
                .ifPresent(free -> free.forEach((type, ids) -> data.freeIds.put(type, new TreeSet<>(ids))));
        }
        if (tag.contains("remembered")) {
            REMEMBERED_CODEC.parse(NbtOps.INSTANCE, tag.get("remembered"))
                .resultOrPartial(err -> CCWirelessPeripherals.LOG.error("Failed to read remembered wireless link names: {}", err))
                .ifPresent(list -> {
                    for (var r : list) {
                        if (data.isLive(r.name())) continue;
                        data.putRemembered(r);
                    }
                });
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var entries = new ArrayList<Entry>(hosts.size());
        hosts.forEach((k, v) -> entries.add(new Entry(k, List.copyOf(v))));
        ENTRIES_CODEC.encodeStart(NbtOps.INSTANCE, entries)
            .resultOrPartial(err -> CCWirelessPeripherals.LOG.error("Failed to save wireless links: {}", err))
            .ifPresent(t -> tag.put("hosts", t));
        NEXT_IDS_CODEC.encodeStart(NbtOps.INSTANCE, nextIds)
            .resultOrPartial(err -> CCWirelessPeripherals.LOG.error("Failed to save wireless link name counters: {}", err))
            .ifPresent(t -> tag.put("next_ids", t));
        var free = new HashMap<String, List<Integer>>();
        freeIds.forEach((type, ids) -> {
            if (!ids.isEmpty()) free.put(type, List.copyOf(ids));
        });
        FREE_IDS_CODEC.encodeStart(NbtOps.INSTANCE, free)
            .resultOrPartial(err -> CCWirelessPeripherals.LOG.error("Failed to save freed wireless link names: {}", err))
            .ifPresent(t -> tag.put("free_ids", t));
        REMEMBERED_CODEC.encodeStart(NbtOps.INSTANCE, List.copyOf(remembered.values()))
            .resultOrPartial(err -> CCWirelessPeripherals.LOG.error("Failed to save remembered wireless link names: {}", err))
            .ifPresent(t -> tag.put("remembered", t));
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

    /** Every link to a peripheral position, across all hosts. */
    public List<HostLink> linksTo(ResourceKey<Level> dimension, BlockPos pos) {
        var dim = targetIndex.get(dimension);
        var linked = dim == null ? null : dim.get(pos);
        if (linked == null || linked.isEmpty()) return List.of();
        var result = new ArrayList<HostLink>(linked.size());
        for (var host : linked) {
            var link = findLink(host, pos);
            if (link != null) result.add(new HostLink(host, link));
        }
        return result;
    }

    /**
     * Add a link. Use {@link #createLink} to make it, so it gets a proper name. Replaces any link from the same host to
     * the same position.
     */
    public void addLink(HostKey host, Link link) {
        var list = hosts.computeIfAbsent(host, k -> new ArrayList<>());
        if (list.isEmpty()) index(host);
        // Count the new name first, so a replaced link with the same name is not treated as freed.
        nameUsed(link.name());
        var it = list.iterator();
        while (it.hasNext()) {
            var old = it.next();
            if (!old.pos().equals(link.pos())) continue;
            it.remove();
            nameReleased(host.dimension(), old, true);
        }
        list.add(link);
        indexTarget(host, link.pos());
        changed();
    }

    public boolean removeLink(HostKey host, BlockPos pos) {
        var list = hosts.get(host);
        if (list == null) return false;
        Link removed = null;
        var it = list.iterator();
        while (it.hasNext()) {
            var link = it.next();
            if (!link.pos().equals(pos)) continue;
            it.remove();
            removed = link;
        }
        if (removed == null) return false;
        unindexTarget(host, pos);
        nameReleased(host.dimension(), removed, true);
        if (list.isEmpty()) removeHost(host);
        else changed();
        return true;
    }

    /** Swap a link for an updated copy (same position). A changed name frees the old one, without remembering it. */
    public boolean replaceLink(HostKey host, Link oldLink, Link newLink) {
        var list = hosts.get(host);
        if (list == null) return false;
        var idx = list.indexOf(oldLink);
        if (idx < 0) return false;
        list.set(idx, newLink);
        if (!oldLink.name().equals(newLink.name())) {
            nameUsed(newLink.name());
            nameReleased(host.dimension(), oldLink, false);
        }
        changed();
        return true;
    }

    public boolean removeHost(HostKey host) {
        var removed = hosts.remove(host);
        if (removed == null) return false;
        for (var link : removed) {
            unindexTarget(host, link.pos());
            nameReleased(host.dimension(), link, true);
        }
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

    // ------------------------------------------------------------------
    // Naming
    // ------------------------------------------------------------------

    /**
     * Make a link for a peripheral, choosing its name: the name remembered for this block (a custom one, or an
     * automatic one of the same type) if no other link uses it, otherwise a new automatic name. The link still has to
     * be passed to {@link #addLink}.
     */
    public Link createLink(ResourceKey<Level> dimension, BlockPos pos, Direction face, String type) {
        var block = GlobalPos.of(dimension, pos.immutable());
        var mem = remembered.get(block);
        if (mem != null && !isLive(mem.name()) && (mem.custom() || mem.type().equals(type))) {
            forgetRemembered(block);
            if (!mem.custom()) unfree(type, mem.name());
            changed();
            return new Link(pos, face, type, mem.name(), mem.custom());
        }
        return new Link(pos, face, type, allocateAuto(type), false);
    }

    /**
     * The peripheral at a link changed type. A custom name is kept; an automatic name is replaced by one for the new
     * type.
     */
    public Link retype(HostKey host, Link link, String type) {
        var updated = link.custom()
            ? link.withName(type, link.name(), true)
            : link.withName(type, allocateAuto(type), false);
        replaceLink(host, link, updated);
        return updated;
    }

    /**
     * Give a link a custom name, or with {@code name == null} go back to an automatic name. A custom name that is
     * remembered for another (unlinked) block is taken from it.
     */
    public RenameResult rename(HostKey host, Link link, @Nullable String name) {
        if (!link.equals(findLink(host, link.pos()))) return new RenameResult(null, "message.ccwirelessperipherals.not_linked");
        if (name == null) {
            if (!link.custom()) return new RenameResult(link, null);
            var updated = link.withName(link.type(), allocateAuto(link.type()), false);
            replaceLink(host, link, updated);
            return new RenameResult(updated, null);
        }

        if (name.equals(link.name())) return new RenameResult(link, null);
        var error = LinkNames.validateCustom(name);
        if (error != null) return new RenameResult(null, error);
        if (isLive(name)) return new RenameResult(null, "message.ccwirelessperipherals.name_taken");

        var owner = rememberedByName.get(name);
        if (owner != null) forgetRemembered(owner);
        var updated = link.withName(link.type(), name, true);
        replaceLink(host, link, updated);
        return new RenameResult(updated, null);
    }

    /** Whether any link currently uses {@code name}. */
    public boolean isLive(String name) {
        return liveNames.containsKey(name);
    }

    /** The name remembered for an unlinked peripheral block, if any. */
    public @Nullable String rememberedName(ResourceKey<Level> dimension, BlockPos pos) {
        var mem = remembered.get(GlobalPos.of(dimension, pos));
        return mem == null ? null : mem.name();
    }

    private String allocateAuto(String type) {
        if (Config.REUSE_FREED_NAMES.get()) {
            var free = freeIds.get(type);
            while (free != null && !free.isEmpty()) {
                var name = LinkNames.auto(type, free.pollFirst());
                if (isLive(name)) continue;
                var owner = rememberedByName.get(name);
                if (owner != null) forgetRemembered(owner);
                changed();
                return name;
            }
        }

        int id = nextIds.getOrDefault(type, 0);
        String name;
        do {
            name = LinkNames.auto(type, id++);
        } while (isLive(name) || rememberedByName.containsKey(name));
        nextIds.put(type, id);
        changed();
        return name;
    }

    private void nameUsed(String name) {
        liveNames.merge(name, 1, Integer::sum);
    }

    /** A link stopped using its name. {@code remember}: keep the name for the block, so relinking it restores it. */
    private void nameReleased(ResourceKey<Level> dimension, Link link, boolean remember) {
        var name = link.name();
        var count = liveNames.getOrDefault(name, 0);
        if (count > 1) {
            liveNames.put(name, count - 1);
            return;
        }
        liveNames.remove(name);

        if (remember) putRemembered(new Remembered(GlobalPos.of(dimension, link.pos()), link.type(), name, link.custom()));
        if (!link.custom()) {
            var id = LinkNames.parseAuto(link.type(), name);
            // Only numbers our counter handed out; anything else (old wired-style names) is never reused.
            if (id >= 0 && id < nextIds.getOrDefault(link.type(), 0)) freeIds.computeIfAbsent(link.type(), k -> new TreeSet<>()).add(id);
        }
    }

    private void putRemembered(Remembered entry) {
        forgetRemembered(entry.block());
        var other = rememberedByName.get(entry.name());
        if (other != null) forgetRemembered(other);
        remembered.put(entry.block(), entry);
        rememberedByName.put(entry.name(), entry.block());
        while (remembered.size() > MAX_REMEMBERED) forgetRemembered(remembered.keySet().iterator().next());
    }

    private void forgetRemembered(GlobalPos block) {
        var old = remembered.remove(block);
        if (old != null) rememberedByName.remove(old.name(), block);
    }

    private void unfree(String type, String name) {
        var id = LinkNames.parseAuto(type, name);
        var free = freeIds.get(type);
        if (id >= 0 && free != null) free.remove(id);
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
