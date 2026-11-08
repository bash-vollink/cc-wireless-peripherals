# CC: Wireless Peripherals

NeoForge add-on for CC: Tweaked (Minecraft 1.21.1, NeoForge 21.1.249, CC: Tweaked 1.120.2+). It links peripherals to a
wireless/ender modem, a CC: Modern server, or a pocket computer or turtle with an ender modem, without cables.
Computers then use them through the normal `peripheral` API.

## Use
1. Craft a **Peripheral Linker** (redstone / wireless modem / stick, stacked vertically).
2. Select a host:
   - Right-click a wireless modem, an ender modem, or a CC: Modern server.
   - **Turtle:** sneak + right-click it. A plain right-click links the turtle as a peripheral instead.
   - **Pocket computer:** hold it in your off hand, with the linker in your main hand, and right-click the **air**. Clicking
     a block links that block as usual.
3. Right-click peripherals to link them. Click a linked block again to unlink it. Sneak + right-click air clears the selection.
4. Hold the linker to see the lines: green = attached, red = out of range, unloaded, blacklisted or in another dimension,
   yellow = no computer is using the host. Looking at a linked block shows its name under the crosshair.

Breaking a linked peripheral removes its links. Breaking a placed host removes all of that host's links.

```lua
peripheral.getNames()                       --> { "top", "minecraft:chest_w0", "storage", ... }
peripheral.wrap("minecraft:chest_w0").size()
peripheral.find("monitor")
```

### Pocket computers and turtles
- They can only host with an **ender modem**. One with a normal wireless modem can't be selected, and links stored for it
  are not exposed.
- Their links belong to the computer's id, so they keep working as the device moves. The computer has to have been turned
  on once to get an id.
- Range is unlimited within a dimension. Links to blocks in another dimension stay stored but are unreachable until the
  device is back in that dimension. The range settings never apply.
- They are only reachable while running somewhere loaded: a pocket computer while it's running (for example in a
  player's inventory), a turtle in a loaded chunk. Nothing happens while they're away, and breaking or picking up a turtle keeps its
  links.
- Copies of a pocket computer with the same id (e.g. from creative) share links, as they share files.

### Names
- **One name per peripheral block, shared by every host that links it**, like on a wired network. Renaming a block
  renames it for every host.
- Linked peripherals get names like `minecraft:chest_w0`. The `w` keeps them apart from wired modem names
  (`minecraft:chest_0`). The mod keeps its own counter and never uses CC's `ids.json`.
- A block keeps its name when it is unlinked and linked again, or broken and replaced in the same spot, even with a
  different host.
- **Renaming:** rename an item to the name you want in an anvil, then sneak + right-click a linked block with it. With an
  unnamed linker, the same click gives the block an automatic name again. You can also use `/ccwp rename <pos> [name]`
  (no name resets it); that is the way to rename a turtle linked as a peripheral, since sneak + right-click selects it.
- Custom names must be unique, use only letters, digits and `_ : . - /`, must not be a side (`top`, `left`, ...), and must
  not end in `_<number>` or `_w<number>`.
- A custom name stays when the peripheral's type changes. An automatic name gets a new one for the new type, like wired
  modems do.
- **Older saves** stored a name per link. On load, a block linked by several hosts under different names keeps one: a
  custom name wins over an automatic one, otherwise the first one in the save wins. Each merge is logged.

## Config (`serverconfig/ccwirelessperipherals-server.toml`)
- `wirelessLinkRange` (16): range of placed wireless (non-ender) hosts.
- `enderLinkRange` (0 = unlimited, like CC's ender modems): range of placed ender hosts (ender modems, ender CC: Modern
  servers). Pocket computers and turtles ignore it. A config file created by an older version keeps its old value
  (64) until you change it.
- `maxLinksPerHost` (32), `reuseFreedNames` (false: reuse numbers of automatic names that are no longer used, so they stay
  small; a reused name may then point at a different block), `peripheralBlacklist` (block ids, `#tags`,
  `type:<peripheralType>`), `hostBlacklist` (placed hosts only), `maxResolvesPerTick`.

Block tags `#ccwirelessperipherals:hosts`, `ender_hosts`, `host_blacklist`, `link_blacklist` can be edited by datapacks.
Client config: `lineRenderDistance`, `renderThroughWalls`.

Commands: `/ccwp list [pos | computer <id>]` (no argument: the host selected on your linker),
`/ccwp rename <peripheral pos> [name]`, `/ccwp clear <pos> | computer <id>` (op), `/ccwp reload` (op).

## Development
- `./gradlew build` builds the jar into `build/libs/`.
- `./gradlew runGameTestServer` runs end-to-end tests against real CC. Put a CC: Modern jar in `run/mods` to include the server test.
- `python3 tools/test_lua_patch.py <cc peripheral.lua> src/main/resources/data/computercraft/lua/rom/autorun/wirelessperipherals.lua [cc_modern.lua]` runs the Lua patch tests (needs `pip install lupa`).
