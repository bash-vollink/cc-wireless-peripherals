package dev.ccwp.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
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
 * Server-wide storage of every wireless link, keyed by host, and of the per-block names (see {@link LinkNames}). Only
 * accessed from the server thread.
 * <p>
 * <b>Names belong to peripheral blocks</b> (dimension + position), not to links: every host linking a block exposes
 * it under the same name, and renaming it renames it for all of them. Names are unique across blocks.
 * <ul>
 *     <li>A linked block always has a name. When its last link goes away the name is kept for the block (up to
 *     {@link #MAX_REMEMBERED} unlinked blocks, oldest dropped first), so linking it again restores it.</li>
 *     <li>Automatic numbers that are no longer used by a linked block go into a per-type pool and, if
 *     {@link Config#REUSE_FREED_NAMES} is on, are handed out again before the counter grows. A name taken from the
 *     pool is no longer remembered for its old block.</li>
 * </ul>
 * Saves from before per-block names stored a name per link. On load, a block linked by several hosts with different
 * names keeps one: a custom name wins over an automatic one, otherwise the first one in the save wins. Dropped
 * automatic names go into the pool.
 */
public final class LinkSavedData extends SavedData {
    private static final String ID = CCWirelessPeripherals.MOD_ID + "_links";

    private record Entry(HostKey host, List<Link.Stored> links) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
            HostKey.CODEC.fieldOf("host").forGetter(Entry::host),
            Link.Stored.CODEC.listOf().fieldOf("links").forGetter(Entry::links)
        ).apply(i, Entry::new));
    }

    /** The name of a peripheral block. */
    private record BlockName(GlobalPos block, String type, String name, boolean custom) {
        static final Codec<BlockName> CODEC = RecordCodecBuilder.create(i -> i.group(
            GlobalPos.CODEC.fieldOf("block").forGetter(BlockName::block),
            Codec.STRING.fieldOf("type").forGetter(BlockName::type),
            Codec.STRING.fieldOf("name").forGetter(BlockName::name),
            Codec.BOOL.optionalFieldOf("custom", false).forGetter(BlockName::custom)
        ).apply(i, BlockName::new));
    }

    private static final Codec<List<Entry>> ENTRIES_CODEC = Entry.CODEC.listOf();
    private static final Codec<List<BlockName>> NAMES_CODEC = BlockName.CODEC.listOf();
    private static final Codec<Map<String, Integer>> NEXT_IDS_CODEC = Codec.unboundedMap(Codec.STRING, Codec.INT);
    private static final Codec<Map<String, List<Integer>>> FREE_IDS_CODEC = Codec.unboundedMap(Codec.STRING, Codec.INT.listOf());

    /** Oldest names of unlinked blocks are forgotten beyond this many. */
    static final int MAX_REMEMBERED = 4096;
    /** Type used for a linked block whose type is unknown (only from damaged saves); fixed on its next refresh. */
    private static final String UNKNOWN_TYPE = "unknown";

    /** A link together with the host that owns it. */
    public record HostLink(HostKey host, Link link) {
    }

    /** Outcome of {@link #rename}: the block's name afterwards, or a translation key explaining the refusal. */
    public record RenameResult(@Nullable String name, @Nullable String error) {
    }

    private final Map<HostKey, List<Link>> hosts = new LinkedHashMap<>();
    /** dimension -> chunk (of host) -> placed hosts in that chunk. */
    private final Map<ResourceKey<Level>, Long2ObjectMap<Set<HostKey.Placed>>> chunkIndex = new HashMap<>();
    /** peripheral block -> hosts linking it. A block is "linked" iff it is a key here. */
    private final Map<GlobalPos, Set<HostKey>> targetIndex = new HashMap<>();
    /** Incremented on every change; cheap change detection for the client sync. */
    private int revision;

    // ---- naming ----
    /** Names of linked blocks and of remembered unlinked blocks, in load/creation order. */
    private final Map<GlobalPos, BlockName> names = new LinkedHashMap<>();
    private final Map<String, GlobalPos> byName = new HashMap<>();
    /** Unlinked blocks that still have a name, oldest first. */
    private final LinkedHashSet<GlobalPos> unlinked = new LinkedHashSet<>();
    /** type -> next number for automatic names. */
    private final Map<String, Integer> nextIds = new HashMap<>();
    /** type -> automatic name numbers not used by any linked block. */
    private final Map<String, TreeSet<Integer>> freeIds = new HashMap<>();

    public static LinkSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(LinkSavedData::new, LinkSavedData::load, null), ID);
    }

    /** Public for tests; normally only reached through {@link #get}. */
    public static LinkSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new LinkSavedData();
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

        // Current saves: one name per block. Linked blocks first, then unlinked ones oldest first.
        List<BlockName> blockNames = new ArrayList<>();
        if (tag.contains("names")) {
            NAMES_CODEC.parse(NbtOps.INSTANCE, tag.get("names"))
                .resultOrPartial(err -> CCWirelessPeripherals.LOG.error("Failed to read wireless link names: {}", err))
                .ifPresent(blockNames::addAll);
        }
        for (var n : blockNames) data.loadName(n);

        Map<GlobalPos, String> legacyTypes = new HashMap<>();
        ENTRIES_CODEC.parse(NbtOps.INSTANCE, tag.get("hosts"))
            .resultOrPartial(err -> CCWirelessPeripherals.LOG.error("Failed to read wireless links: {}", err))
            .ifPresent(entries -> {
                for (var entry : entries) {
                    var host = entry.host();
                    for (var stored : entry.links()) {
                        var dimension = stored.dimension().orElse(host instanceof HostKey.Placed p ? p.dimension() : null);
                        if (dimension == null) continue; // Mobile hosts always store the dimension.
                        var link = new Link(GlobalPos.of(dimension, stored.pos().immutable()), stored.face());
                        data.putLink(host, link);
                        stored.type().ifPresent(t -> legacyTypes.putIfAbsent(link.target(), t));
                        if (stored.name().isPresent()) {
                            data.mergeLegacyName(link.target(), stored.type().orElse(UNKNOWN_TYPE), stored.name().get(), stored.custom());
                        }
                    }
                }
            });

        // Saves from the first naming version remembered names of unlinked blocks separately.
        if (tag.contains("remembered")) {
            NAMES_CODEC.parse(NbtOps.INSTANCE, tag.get("remembered"))
                .resultOrPartial(err -> CCWirelessPeripherals.LOG.error("Failed to read remembered wireless link names: {}", err))
                .ifPresent(list -> {
                    for (var n : list) if (!data.names.containsKey(n.block())) data.loadName(n);
                });
        }

        // Sort out the link state of every named block, and give any linked block without a name one.
        for (var block : new ArrayList<>(data.names.keySet())) {
            if (!data.targetIndex.containsKey(block)) data.unlinked.add(block);
        }
        for (var block : data.targetIndex.keySet()) {
            if (!data.names.containsKey(block)) {
                var type = legacyTypes.getOrDefault(block, UNKNOWN_TYPE);
                data.putName(new BlockName(block, type, data.allocateAuto(type, false), false));
            }
        }
        data.trimUnlinked();
        return data;
    }

    private void loadName(BlockName n) {
        if (byName.containsKey(n.name()) || names.containsKey(n.block())) {
            CCWirelessPeripherals.LOG.warn("Dropping duplicate wireless link name {} for {}", n.name(), n.block());
            return;
        }
        putName(n);
    }

    private void mergeLegacyName(GlobalPos block, String type, String name, boolean custom) {
        var existing = names.get(block);
        if (existing != null) {
            if (existing.name().equals(name)) return;
            if (existing.custom() || !custom) {
                CCWirelessPeripherals.LOG.info("Merging wireless link names for {}: keeping {}, dropping {}", block, existing.name(), name);
                if (!custom) poolAuto(type, name);
                return;
            }
            CCWirelessPeripherals.LOG.info("Merging wireless link names for {}: keeping custom {}, dropping {}", block, name, existing.name());
            removeName(block);
            if (!existing.custom()) poolAuto(existing.type(), existing.name());
        }
        var owner = byName.get(name);
        if (owner != null) {
            CCWirelessPeripherals.LOG.warn("Wireless link name {} is used by both {} and {}; {} gets a new name", name, owner, block, block);
            return;
        }
        putName(new BlockName(block, type, name, custom));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var entries = new ArrayList<Entry>(hosts.size());
        hosts.forEach((k, v) -> entries.add(new Entry(k, v.stream().map(Link.Stored::of).toList())));
        ENTRIES_CODEC.encodeStart(NbtOps.INSTANCE, entries)
            .resultOrPartial(err -> CCWirelessPeripherals.LOG.error("Failed to save wireless links: {}", err))
            .ifPresent(t -> tag.put("hosts", t));

        var blockNames = new ArrayList<BlockName>(names.size());
        for (var n : names.values()) if (!unlinked.contains(n.block())) blockNames.add(n);
        for (var block : unlinked) blockNames.add(names.get(block));
        NAMES_CODEC.encodeStart(NbtOps.INSTANCE, blockNames)
            .resultOrPartial(err -> CCWirelessPeripherals.LOG.error("Failed to save wireless link names: {}", err))
            .ifPresent(t -> tag.put("names", t));

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
        return tag;
    }

    public int revision() {
        return revision;
    }

    // ------------------------------------------------------------------
    // Links
    // ------------------------------------------------------------------

    public List<Link> getLinks(HostKey host) {
        var links = hosts.get(host);
        return links == null ? List.of() : Collections.unmodifiableList(links);
    }

    public Set<HostKey> hosts() {
        return Collections.unmodifiableSet(hosts.keySet());
    }

    public @Nullable Link findLink(HostKey host, GlobalPos target) {
        var links = hosts.get(host);
        if (links == null) return null;
        for (var link : links) if (link.target().equals(target)) return link;
        return null;
    }

    /** Hosts linking a peripheral block. */
    public Set<HostKey> hostsLinking(GlobalPos target) {
        var set = targetIndex.get(target);
        return set == null ? Set.of() : Collections.unmodifiableSet(set);
    }

    public boolean isLinked(GlobalPos target) {
        return targetIndex.containsKey(target);
    }

    /**
     * Link a host to a peripheral block, replacing any link from the same host to that block. The block keeps its
     * name if it has one: the name of another host's link, or the one remembered for it. A remembered automatic name
     * for a different type is replaced, like on a type change.
     */
    public Link addLink(HostKey host, GlobalPos target, Direction face, String type) {
        target = GlobalPos.of(target.dimension(), target.pos().immutable());
        var link = new Link(target, face);
        putLink(host, link);
        claimName(target, type);
        changed();
        return link;
    }

    private void putLink(HostKey host, Link link) {
        var list = hosts.computeIfAbsent(host, k -> new ArrayList<>());
        if (list.isEmpty()) index(host);
        list.removeIf(l -> l.target().equals(link.target()));
        list.add(link);
        targetIndex.computeIfAbsent(link.target(), k -> new HashSet<>(2)).add(host);
    }

    public boolean removeLink(HostKey host, GlobalPos target) {
        var list = hosts.get(host);
        if (list == null || !list.removeIf(l -> l.target().equals(target))) return false;
        unindexTarget(host, target);
        if (list.isEmpty()) removeHost(host);
        else changed();
        return true;
    }

    public boolean removeHost(HostKey host) {
        var removed = hosts.remove(host);
        if (removed == null) return false;
        for (var link : removed) unindexTarget(host, link.target());
        if (host instanceof HostKey.Placed placed) {
            var dim = chunkIndex.get(placed.dimension());
            if (dim != null) {
                var chunk = ChunkPos.asLong(placed.pos());
                var set = dim.get(chunk);
                if (set != null) {
                    set.remove(placed);
                    if (set.isEmpty()) dim.remove(chunk);
                }
            }
        }
        changed();
        return true;
    }

    /**
     * Remove every link pointing at a peripheral block (it was broken).
     *
     * @return The hosts that lost a link.
     */
    public Set<HostKey> removeLinksTo(GlobalPos target) {
        var linked = targetIndex.get(target);
        if (linked == null || linked.isEmpty()) return Set.of();
        var affected = Set.copyOf(linked);
        for (var host : affected) removeLink(host, target);
        return affected;
    }

    /** Visit placed hosts whose block lies within {@code chunkRadius} chunks of {@code centre}. */
    public void forHostsNear(ResourceKey<Level> dimension, BlockPos centre, int chunkRadius, Consumer<HostKey.Placed> consumer) {
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

    private void unindexTarget(HostKey host, GlobalPos target) {
        var set = targetIndex.get(target);
        if (set == null) return;
        set.remove(host);
        if (!set.isEmpty()) return;
        targetIndex.remove(target);
        // The block's last link is gone: remember its name, and free an automatic number.
        var name = names.get(target);
        if (name != null) {
            unlinked.remove(target);
            unlinked.add(target);
            if (!name.custom()) poolAuto(name.type(), name.name());
            trimUnlinked();
        }
    }

    private void index(HostKey host) {
        if (!(host instanceof HostKey.Placed placed)) return;
        chunkIndex.computeIfAbsent(placed.dimension(), k -> new Long2ObjectOpenHashMap<>())
            .computeIfAbsent(ChunkPos.asLong(placed.pos()), k -> new HashSet<>())
            .add(placed);
    }

    // ------------------------------------------------------------------
    // Names
    // ------------------------------------------------------------------

    /** The name of a peripheral block: always set while it is linked, and possibly remembered after. */
    public @Nullable String nameOf(GlobalPos block) {
        var n = names.get(block);
        return n == null ? null : n.name();
    }

    /** The peripheral type last seen at a block. */
    public @Nullable String typeOf(GlobalPos block) {
        var n = names.get(block);
        return n == null ? null : n.type();
    }

    public boolean isCustomName(GlobalPos block) {
        var n = names.get(block);
        return n != null && n.custom();
    }

    /** The name remembered for an unlinked block, if any. */
    public @Nullable String rememberedName(GlobalPos block) {
        return unlinked.contains(block) ? nameOf(block) : null;
    }

    /** Whether a linked block uses {@code name}. */
    public boolean isLive(String name) {
        var owner = byName.get(name);
        return owner != null && targetIndex.containsKey(owner);
    }

    /**
     * The peripheral at a linked block changed type. A custom name is kept; an automatic name is replaced by one for
     * the new type. Callers must refresh every host linking the block.
     *
     * @return The block's name afterwards.
     */
    public String retype(GlobalPos block, String type) {
        var n = names.get(block);
        if (n == null) {
            putName(new BlockName(block, type, allocateAuto(type, true), false));
        } else if (!n.type().equals(type)) {
            if (n.custom()) {
                putName(new BlockName(block, type, n.name(), true));
            } else {
                removeName(block);
                poolAuto(n.type(), n.name());
                putName(new BlockName(block, type, allocateAuto(type, true), false));
            }
        }
        changed();
        return nameOf(block);
    }

    /**
     * Give a linked block a custom name, or with {@code name == null} go back to an automatic name. The name applies
     * to every host linking the block. A custom name remembered for another (unlinked) block is taken from it.
     * Callers must refresh every host linking the block.
     */
    public RenameResult rename(GlobalPos block, @Nullable String name) {
        var n = names.get(block);
        if (n == null || !isLinked(block)) return new RenameResult(null, "message.ccwirelessperipherals.not_linked");
        if (name == null) {
            if (!n.custom()) return new RenameResult(n.name(), null);
            removeName(block);
            putName(new BlockName(block, n.type(), allocateAuto(n.type(), true), false));
            changed();
            return new RenameResult(nameOf(block), null);
        }

        if (name.equals(n.name())) return new RenameResult(name, null);
        var error = LinkNames.validateCustom(name);
        if (error != null) return new RenameResult(null, error);
        if (isLive(name)) return new RenameResult(null, "message.ccwirelessperipherals.name_taken");

        var owner = byName.get(name);
        if (owner != null) forget(owner);
        removeName(block);
        if (!n.custom()) poolAuto(n.type(), n.name());
        putName(new BlockName(block, n.type(), name, true));
        changed();
        return new RenameResult(name, null);
    }

    /** Make sure a block that just gained a link has a name for {@code type}. */
    private void claimName(GlobalPos block, String type) {
        var n = names.get(block);
        var wasUnlinked = unlinked.remove(block);
        if (n == null) {
            putName(new BlockName(block, type, allocateAuto(type, true), false));
            return;
        }
        if (n.custom()) {
            if (!n.type().equals(type)) putName(new BlockName(block, type, n.name(), true));
            return;
        }
        if (n.type().equals(type)) {
            // Its number was in the pool while the block was unlinked.
            if (wasUnlinked) unfree(type, n.name());
            return;
        }
        retype(block, type);
    }

    private String allocateAuto(String type, boolean allowReuse) {
        if (allowReuse && Config.REUSE_FREED_NAMES.get()) {
            var free = freeIds.get(type);
            while (free != null && !free.isEmpty()) {
                var name = LinkNames.auto(type, free.pollFirst());
                var owner = byName.get(name);
                if (owner != null) {
                    if (targetIndex.containsKey(owner)) continue;
                    forget(owner);
                }
                return name;
            }
        }

        int id = nextIds.getOrDefault(type, 0);
        String name;
        do {
            name = LinkNames.auto(type, id++);
        } while (byName.containsKey(name));
        nextIds.put(type, id);
        return name;
    }

    private void poolAuto(String type, String name) {
        var id = LinkNames.parseAuto(type, name);
        // Only numbers our counter handed out; anything else (old wired-style names) is never reused.
        if (id >= 0 && id < nextIds.getOrDefault(type, 0)) freeIds.computeIfAbsent(type, k -> new TreeSet<>()).add(id);
    }

    private void unfree(String type, String name) {
        var id = LinkNames.parseAuto(type, name);
        var free = freeIds.get(type);
        if (id >= 0 && free != null) free.remove(id);
    }

    private void putName(BlockName n) {
        var old = names.put(n.block(), n);
        if (old != null && !old.name().equals(n.name())) byName.remove(old.name(), n.block());
        byName.put(n.name(), n.block());
    }

    private void removeName(GlobalPos block) {
        var old = names.remove(block);
        if (old != null) byName.remove(old.name(), block);
    }

    /** Drop the name of an unlinked block. */
    private void forget(GlobalPos block) {
        if (targetIndex.containsKey(block)) return;
        unlinked.remove(block);
        removeName(block);
    }

    private void trimUnlinked() {
        while (unlinked.size() > MAX_REMEMBERED) forget(unlinked.iterator().next());
    }

    private void changed() {
        revision++;
        setDirty();
    }
}
