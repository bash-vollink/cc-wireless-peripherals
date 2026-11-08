First test build of CC: Wireless Peripherals.

**Requires:** Minecraft 1.21.1, NeoForge 21.1.x (built against 21.1.249), CC: Tweaked 1.120.2+. CC: Modern is optional.

- Peripheral Linker item: right-click a wireless/ender modem or a CC: Modern server to select it, then right-click peripherals to link or unlink them.
- Linked peripherals show up through the normal `peripheral` API (`getNames`, `wrap`, `find`, events), the same way wired ones do.
- Lines show while you hold the linker: green = attached, red = missing/out of range, yellow = no computer is using the host.
- Server config covers ranges, the per-host link cap, and the peripheral and host blacklists. Commands: `/ccwp list|clear|reload`.

Automated game tests pass, but the link-line rendering hasn't been checked in a real client yet.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
