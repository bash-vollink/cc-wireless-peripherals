First test build of CC: Wireless Peripherals.

**Requires:** Minecraft 1.21.1, NeoForge 21.1.x (built against 21.1.249), CC: Tweaked 1.120.2+. CC: Modern is optional.

- Peripheral Linker item: right-click a wireless/ender modem or a CC: Modern server to select it, then right-click peripherals to link or unlink them.
- Pocket computers and turtles with an **ender modem** can be hosts too. Sneak + right-click a turtle, or hold a pocket computer in your off hand and right-click the air. Their links follow the computer's id, so they keep working as it moves. Range is unlimited within a dimension, and links in another dimension wait until it's back.
- Linked peripherals show up through the normal `peripheral` API (`getNames`, `wrap`, `find`, events), the same way wired ones do.
- Ender hosts have unlimited range by default, like CC's ender modems. Placed wireless modems keep a 16-block range.
- Lines show while you hold the linker: green = attached, red = out of range/unloaded/blacklisted/other dimension, yellow = no computer is using the host. The name of the linked block under the crosshair is shown too.
- Each peripheral block has one name, shared by every host that links it, like `minecraft:chest_w0`. The `w` keeps it apart from wired modem names, and the mod doesn't touch CC's `ids.json`. A block keeps its name when it is unlinked and relinked, or broken and replaced in the same spot.
- Peripherals can be renamed: rename the linker in an anvil, then sneak + right-click a linked block. An unnamed linker resets it to an automatic name. `/ccwp rename <pos> [name]` does the same. A rename applies to every host, and custom names stay when the peripheral's type changes.
- Server config covers ranges, the per-host link cap, the peripheral and host blacklists, and `reuseFreedNames` (off by default), which keeps automatic name numbers small by reusing unused ones. Commands: `/ccwp list|rename|clear|reload`.

Automated game tests pass. Not yet checked in a real client: the link lines, the crosshair names, renaming with the linker, and selecting pocket computers and turtles.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
