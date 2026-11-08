package dev.ccwp;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Host/peripheral rules: which blocks can own links and which can be linked. Combines block tags (datapack-editable)
 * with the server config lists. Parsed lists are cached and rebuilt when the config reloads.
 */
public final class Blacklists {
    public static final TagKey<Block> HOSTS = tag("hosts");
    public static final TagKey<Block> ENDER_HOSTS = tag("ender_hosts");
    public static final TagKey<Block> HOST_BLACKLIST_TAG = tag("host_blacklist");
    public static final TagKey<Block> LINK_BLACKLIST_TAG = tag("link_blacklist");

    private static volatile Rules peripheralRules = Rules.EMPTY;
    private static volatile Rules hostRules = Rules.EMPTY;

    private Blacklists() {
    }

    private static TagKey<Block> tag(String name) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(CCWirelessPeripherals.MOD_ID, name));
    }

    /** Re-parse config lists. Called on config load/reload. */
    public static void reload() {
        try {
            peripheralRules = Rules.parse(Config.PERIPHERAL_BLACKLIST.get());
            hostRules = Rules.parse(Config.HOST_BLACKLIST.get());
        } catch (IllegalStateException e) {
            // Config not loaded yet (e.g. on a client without a server config).
            peripheralRules = Rules.EMPTY;
            hostRules = Rules.EMPTY;
        }
    }

    /** Whether this block may own wireless links. */
    public static boolean isHost(BlockState state) {
        return state.is(HOSTS) && !state.is(HOST_BLACKLIST_TAG) && !hostRules.matchesBlock(state);
    }

    /** Whether this block is a known host type at all (ignoring the host blacklist). */
    public static boolean isHostType(BlockState state) {
        return state.is(HOSTS);
    }

    public static boolean isEnderHost(BlockState state) {
        return state.is(ENDER_HOSTS);
    }

    public static int rangeFor(BlockState hostState) {
        return isEnderHost(hostState) ? Config.ENDER_LINK_RANGE.get() : Config.WIRELESS_LINK_RANGE.get();
    }

    /** Whether a peripheral of the given block (and optionally type) may be linked. */
    public static boolean isPeripheralBlacklisted(BlockState state, @Nullable String peripheralType) {
        if (state.is(LINK_BLACKLIST_TAG)) return true;
        var rules = peripheralRules;
        return rules.matchesBlock(state) || (peripheralType != null && rules.types.contains(peripheralType));
    }

    private record Rules(Set<ResourceLocation> blocks, List<TagKey<Block>> tags, Set<String> types) {
        static final Rules EMPTY = new Rules(Set.of(), List.of(), Set.of());

        static Rules parse(List<? extends String> entries) {
            Set<ResourceLocation> blocks = new HashSet<>();
            List<TagKey<Block>> tags = new java.util.ArrayList<>();
            Set<String> types = new HashSet<>();
            for (var raw : entries) {
                var entry = raw.trim();
                if (entry.isEmpty()) continue;
                if (entry.startsWith("type:")) {
                    types.add(entry.substring(5));
                } else if (entry.startsWith("#")) {
                    var id = ResourceLocation.tryParse(entry.substring(1));
                    if (id != null) tags.add(TagKey.create(Registries.BLOCK, id));
                    else CCWirelessPeripherals.LOG.warn("Invalid tag in blacklist: {}", entry);
                } else {
                    var id = ResourceLocation.tryParse(entry);
                    if (id != null) blocks.add(id);
                    else CCWirelessPeripherals.LOG.warn("Invalid block id in blacklist: {}", entry);
                }
            }
            return new Rules(Set.copyOf(blocks), List.copyOf(tags), Set.copyOf(types));
        }

        boolean matchesBlock(BlockState state) {
            if (!blocks.isEmpty() && blocks.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()))) return true;
            for (var tag : tags) if (state.is(tag)) return true;
            return false;
        }
    }
}
