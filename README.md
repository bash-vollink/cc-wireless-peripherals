# CC: Wireless Peripherals

NeoForge add-on for CC: Tweaked (Minecraft 1.21.1, NeoForge 21.1.249, CC: Tweaked 1.120.2+). It links peripherals to a
wireless/ender modem or a CC: Modern server without cables. Computers then use them through the normal `peripheral` API.

## Use
1. Craft a **Peripheral Linker** (redstone / wireless modem / stick, stacked vertically).
2. Right-click a wireless modem, an ender modem, or a CC: Modern server to select it.
3. Right-click peripherals in range to link them. Click a linked block again to unlink it. Sneak + right-click air clears the selection.
4. Hold the linker to see the lines: green = attached, red = out of range, unloaded or blacklisted, yellow = no computer is using the host.
   Looking at a linked block shows its name under the crosshair.

Breaking a linked peripheral removes its link. Breaking a host removes all of that host's links.

```lua
peripheral.getNames()                       --> { "top", "minecraft:chest_w0", "storage", ... }
peripheral.wrap("minecraft:chest_w0").size()
peripheral.find("monitor")
```

### Names
- Linked peripherals get names like `minecraft:chest_w0`. The `w` keeps them apart from wired modem names
  (`minecraft:chest_0`). The mod keeps its own counter and never uses CC's `ids.json`.
- A block keeps its name when it is unlinked and linked again, or broken and replaced in the same spot, even with a
  different host.
- **Renaming:** rename an item to the name you want in an anvil, then sneak + right-click a linked block with it. With an
  unnamed linker, the same click gives the block an automatic name again. You can also use `/ccwp rename <pos> [name]`
  (no name resets it). If a block is linked to several hosts, the host selected on your linker is used.
- Custom names must be unique, use only letters, digits and `_ : . - /`, must not be a side (`top`, `left`, ...), and must
  not end in `_<number>` or `_w<number>`.
- A custom name stays when the peripheral's type changes. An automatic name gets a new one for the new type, like wired
  modems do. Links made before this naming scheme keep their old names.

## Config (`serverconfig/ccwirelessperipherals-server.toml`)
`wirelessLinkRange` (16), `enderLinkRange` (64), `maxLinksPerHost` (32), `reuseFreedNames` (false: reuse numbers of
automatic names that are no longer used, so they stay small; a reused name may then point at a different block), `peripheralBlacklist`
(block ids, `#tags`, `type:<peripheralType>`), `hostBlacklist`, `maxResolvesPerTick`.
Block tags `#ccwirelessperipherals:hosts`, `ender_hosts`, `host_blacklist`, `link_blacklist` can be edited by datapacks.
Client config: `lineRenderDistance`, `renderThroughWalls`.

Commands: `/ccwp list [pos]`, `/ccwp rename <peripheral pos> [name]`, `/ccwp clear <pos>` (op), `/ccwp reload` (op).

## Development
- `./gradlew build` builds the jar into `build/libs/`.
- `./gradlew runGameTestServer` runs end-to-end tests against real CC. Put a CC: Modern jar in `run/mods` to include the server test.
- `python3 tools/test_lua_patch.py <cc peripheral.lua> src/main/resources/data/computercraft/lua/rom/autorun/wirelessperipherals.lua [cc_modern.lua]` runs the Lua patch tests (needs `pip install lupa`).
