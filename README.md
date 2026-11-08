# CC: Wireless Peripherals

NeoForge add-on for CC: Tweaked (Minecraft 1.21.1, NeoForge 21.1.249, CC: Tweaked 1.120.2+). It links peripherals to a
wireless/ender modem or a CC: Modern server without cables. Computers then use them through the normal `peripheral` API.

## Use
1. Craft a **Peripheral Linker** (redstone / wireless modem / stick, stacked vertically).
2. Right-click a wireless modem, an ender modem, or a CC: Modern server to select it.
3. Right-click peripherals in range to link them. Click a linked block again to unlink it. Sneak + right-click air clears the selection.
4. Hold the linker to see the lines: green = attached, red = out of range, unloaded or blacklisted, yellow = no computer is using the host.

Breaking a linked peripheral removes its link. Breaking a host removes all of that host's links.

```lua
peripheral.getNames()                      --> { "top", "minecraft:chest_0", ... }
peripheral.wrap("minecraft:chest_0").size()
peripheral.find("monitor")
```

## Config (`serverconfig/ccwirelessperipherals-server.toml`)
`wirelessLinkRange` (16), `enderLinkRange` (64), `maxLinksPerHost` (32), `peripheralBlacklist`
(block ids, `#tags`, `type:<peripheralType>`), `hostBlacklist`, `maxResolvesPerTick`.
Block tags `#ccwirelessperipherals:hosts`, `ender_hosts`, `host_blacklist`, `link_blacklist` can be edited by datapacks.
Client config: `lineRenderDistance`, `renderThroughWalls`.

Commands: `/ccwp list [pos]`, `/ccwp clear <pos>` (op), `/ccwp reload` (op).

## Development
- `./gradlew build` builds the jar into `build/libs/`.
- `./gradlew runGameTestServer` runs end-to-end tests against real CC. Put a CC: Modern jar in `run/mods` to include the server test.
- `python3 tools/test_lua_patch.py <cc peripheral.lua> src/main/resources/data/computercraft/lua/rom/autorun/wirelessperipherals.lua [cc_modern.lua]` runs the Lua patch tests (needs `pip install lupa`).
