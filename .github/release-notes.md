First test build of CC: Wireless Peripherals.

**Requires:** Minecraft 1.21.1, NeoForge 21.1.x (built against 21.1.249), CC: Tweaked 1.120.2+. CC: Modern is optional.

- Peripheral Linker item: right-click a wireless/ender modem or a CC: Modern server to select it, then right-click peripherals to link or unlink them.
- Linked peripherals show up through the normal `peripheral` API (`getNames`, `wrap`, `find`, events), the same way wired ones do.
- Lines show while you hold the linker: green = attached, red = out of range/unloaded/blacklisted, yellow = no computer is using the host.
- Holding the linker also shows the name of any linked block under the crosshair.
- Linked peripherals get their own names, like `minecraft:chest_w0`. The `w` keeps them apart from wired modem names, and the mod doesn't touch CC's `ids.json`. A block keeps its name when it is unlinked and relinked, or broken and replaced in the same spot.
- Links can be renamed: rename the linker in an anvil, then sneak + right-click a linked block. An unnamed linker resets the block to an automatic name. `/ccwp rename <pos> [name]` does the same. Custom names stay when the peripheral's type changes.
- Server config covers ranges, the per-host link cap, the peripheral and host blacklists, and `reuseFreedNames` (off by default), which keeps automatic name numbers small by reusing unused ones. Commands: `/ccwp list|rename|clear|reload`.

Automated game tests pass, but the link lines, the crosshair names and renaming with the linker haven't been checked in a real client yet.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
