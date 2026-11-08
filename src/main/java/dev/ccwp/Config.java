package dev.ccwp;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public final class Config {
    private Config() {
    }

    // ---- Server ----
    public static final ModConfigSpec SERVER_SPEC;
    public static final ModConfigSpec.IntValue WIRELESS_LINK_RANGE;
    public static final ModConfigSpec.IntValue ENDER_LINK_RANGE;
    public static final ModConfigSpec.IntValue MAX_LINKS_PER_HOST;
    public static final ModConfigSpec.BooleanValue REUSE_FREED_NAMES;
    public static final ModConfigSpec.IntValue MAX_RESOLVES_PER_TICK;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> PERIPHERAL_BLACKLIST;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> HOST_BLACKLIST;

    // ---- Client ----
    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec.IntValue LINE_RENDER_DISTANCE;
    public static final ModConfigSpec.BooleanValue RENDER_THROUGH_WALLS;

    static {
        var server = new ModConfigSpec.Builder();
        server.push("links");
        WIRELESS_LINK_RANGE = server
            .comment("Maximum distance (in blocks, centre to centre) between a placed wireless (non-ender) host and a linked peripheral.")
            .defineInRange("wirelessLinkRange", 16, 1, 256);
        ENDER_LINK_RANGE = server
            .comment("Maximum distance (in blocks, centre to centre) between a placed ender host and a linked peripheral.",
                "0 = unlimited within the dimension, like CC's ender modems. Placed hosts count as ender hosts when they",
                "are in the #ccwirelessperipherals:ender_hosts block tag. Pocket computers and turtles (which can only",
                "host with an ender modem) ignore this and are always unlimited within their dimension.")
            .defineInRange("enderLinkRange", 0, 0, 1_000_000);
        MAX_LINKS_PER_HOST = server
            .comment("Maximum number of peripherals that can be linked to a single host. 0 = unlimited.")
            .defineInRange("maxLinksPerHost", 32, 0, 4096);
        REUSE_FREED_NAMES = server
            .comment("Reuse the numbers of automatic link names (e.g. minecraft:chest_w3) that are no longer in use, so the",
                "numbers stay small. Relinking the same block always restores its old name either way. With this on, a",
                "freed name can go to a different block, so a script that hard-codes it may end up talking to another",
                "peripheral. Wired modems never reuse names.")
            .define("reuseFreedNames", false);
        PERIPHERAL_BLACKLIST = server
            .comment("Peripherals that cannot be linked wirelessly. Entries may be:",
                "  a block id         e.g. \"minecraft:chest\"",
                "  a block tag        e.g. \"#c:chests\"",
                "  a peripheral type  e.g. \"type:turtle\"",
                "The #ccwirelessperipherals:link_blacklist block tag is also honoured.")
            .defineListAllowEmpty("peripheralBlacklist", List.of(), () -> "", Config::isString);
        HOST_BLACKLIST = server
            .comment("Hosts (wireless/ender modems, CC: Modern servers, ...) that may not own wireless links.",
                "Entries may be a block id (\"cc_modern:server_ender\") or a block tag (\"#some:tag\").",
                "The #ccwirelessperipherals:host_blacklist block tag is also honoured.")
            .defineListAllowEmpty("hostBlacklist", List.of(), () -> "", Config::isString);
        server.pop();

        server.push("performance");
        MAX_RESOLVES_PER_TICK = server
            .comment("Upper bound on peripheral lookups performed per server tick. Work only happens when something changed",
                "(a computer attached, a link was edited, or a linked block changed/loaded/unloaded).")
            .defineInRange("maxResolvesPerTick", 64, 1, 10_000);
        server.pop();
        SERVER_SPEC = server.build();

        var client = new ModConfigSpec.Builder();
        LINE_RENDER_DISTANCE = client
            .comment("Show links of hosts within this many blocks while holding the Peripheral Linker.")
            .defineInRange("lineRenderDistance", 48, 8, 256);
        RENDER_THROUGH_WALLS = client
            .comment("Draw link lines through blocks.")
            .define("renderThroughWalls", false);
        CLIENT_SPEC = client.build();
    }

    private static boolean isString(Object o) {
        return o instanceof String;
    }
}
