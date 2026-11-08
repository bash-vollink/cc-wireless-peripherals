# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is
A NeoForge add-on for CC: Tweaked (Minecraft 1.21.1, NeoForge 21.1.249, CC: Tweaked 1.120.2+, Java 21). It links
peripherals to wireless/ender modems and CC: Modern servers without cables. Computers then use them through the
**stock** `peripheral` Lua API (`getNames`/`wrap`/`find` and `peripheral`/`peripheral_detach` events), the same way
wired ones work. Mod id `ccwirelessperipherals`, package `dev.ccwp`.

## Commands
- Build the jar: `./gradlew build`. The output is `build/libs/ccwirelessperipherals-<version>.jar`. `dev/ccwp/test/**` and
  `data/ccwirelessperipherals/structure/**` are excluded from the jar.
- Run all game tests headless: `./gradlew runGameTestServer` (exit code reflects pass/fail).
  - There is no per-test CLI filter. To run one test, start `./gradlew runServer` (or `runClient`) and use
    `/test run ccwirelessperipherals:<lowercased method name>` in-game. Test names are lowercased method names,
    e.g. `linkedperipheralsattachanddetach`.
  - To include the CC: Modern server test, put a CC: Modern jar in `run/mods/`. Without it, that test logs "skipping"
    and passes.
- Lua patch tests (offline, needs `pip install lupa`):
  `python3 tools/test_lua_patch.py <CC rom/apis/peripheral.lua> src/main/resources/data/computercraft/lua/rom/autorun/wirelessperipherals.lua [<cc_modern.lua>]`
  Pull CC's `peripheral.lua` from the CC jar (`data/computercraft/lua/rom/apis/peripheral.lua`).
- `./gradlew runSmokeClient` is a dev-only visual check (`ClientSmokeTest`, `-Dccwp.smoke=true`). It needs a world at
  `run/saves/ccwp_smoke` and is very slow without a GPU.
- CI (`.github/workflows/release.yml`) builds and runs the game tests on every push. A pushed `v*` tag publishes a
  GitHub release with the jar; tags containing `-` become prereleases, and the notes come from
  `.github/release-notes.md`.

## Architecture (the parts that span files)
**How Lua sees linked peripherals.** CC's `rom/apis/peripheral.lua` already sends remote lookups to any *side*
peripheral with the `peripheral_hub` type, by calling `getNamesRemote`/`isPresentRemote`/`getTypeRemote`/
`hasTypeRemote`/`getMethodsRemote`/`callRemote`. We make every CC `WirelessModemPeripheral` look like a wired hub:
- `mixin/WirelessModemPeripheralMixin` adds the `peripheral_hub` type (always, including on turtle and pocket modems)
  and a lazily created `WirelessHub` through `HubHolder`.
- `hub/WirelessHubMethods` is a CC `GenericSource`, registered in common setup. CC attaches generic methods to any
  peripheral class that matches the first parameter type, so the remote methods need no mixin.
- `mixin/ModemPeripheralMixin` forwards `attach`/`detach`/`removed` on modems to the hub.
- `hub/RemoteWrapper` is the `IComputerAccess` each linked peripheral sees, one per computer and peripheral. It
  queues `peripheral`/`peripheral_detach` events and wraps the `ILuaContext` in CC's `GuardedLuaContext`.

**CC: Modern.** Its `ServerModemPeripheral` extends `WirelessModemPeripheral`, so it works automatically. Releases
up to 0.1.1 put the modem on a side. Newer CC: Modern versions nest it inside a virtual `internal` hub that the stock
API doesn't search, so `rom/autorun/wirelessperipherals.lua` wraps the `peripheral` API to also search non-side hubs.
It must run after `cc_modern.lua`; autorun files run alphabetically. CC: Modern is not a compile dependency: hosts
are found by block tag.

**Hosts and links.** A host is a block in `#ccwirelessperipherals:hosts` that is not host-blacklisted
(`Blacklists`). `HostKey` is the block entity position for CC modem blocks (their `getPosition()` returns the block
they're attached to) and `BlockPos.containing(getPosition())` for other hosts. All links live in one server-wide
`data/LinkSavedData` on the overworld, with a chunk index of hosts (for client sync) and a peripheral-position index
(for removing links when a peripheral is broken).

**Names.** Rules are in `data/LinkNames`, state in `LinkSavedData`. Always make links with `LinkSavedData.createLink` and
change names with `rename`/`retype`, never by building a `Link` with a name yourself.
- Automatic names are `type + "_w" + n`, from our own per-type counter. CC's `ids.json` is never used, and the `w`
  means they can't clash with wired `type_<n>` names. Pre-existing links keep their old wired-style names.
- Names are unique across all links. When a block loses its last link, its name is remembered by block position (up to
  `MAX_REMEMBERED`, oldest dropped first), so relinking restores it.
- Freed automatic numbers go into a per-type pool, which is used only when `reuseFreedNames` is on. Taking a name from
  the pool makes its old block forget it.
- Custom names (`Link.custom`) can't end in `_<digits>`/`_w<digits>` or be a side. They survive type changes; automatic
  names are replaced on a type change.

**Threading and tick cost (multiplayer is a requirement).**
- All world access happens in `WirelessHub.refresh`, on the server thread, driven by `HubScheduler`'s dirty queue.
- Hubs are queued on computer attach/detach, link edits, `BlockCapabilityCache` invalidation (block changes and
  chunk load/unload), and config/`/ccwp reload`. Each tick has a lookup budget (`maxResolvesPerTick`), and an idle
  server does no work.
- Don't add polling. Never call `getBlockState` on positions that might be unloaded; check `level.isLoaded` first.
- Peripheral lookup (`hub/PeripheralLookup`) mirrors CC's NeoForge `PeripheralAccess`: `PeripheralCapability` first,
  then `Peripherals.getGenericPeripheral` (inventories, tanks, ...).
- Computer threads only read the hub's concurrent maps and immutable snapshots.
- `publish()` diffs old and new resolved peripherals, so only changes cause detach/attach events.

**Client.** `net/LinkViewSync` sends `LinkViewPayload` every 10 ticks, and only to players holding the linker and
only when the view changed. `client/LinkLineRenderer` draws the lines, and `client/LinkNameOverlay` shows the names of
links to the block under the crosshair. Status order is
`HubScheduler.LinkStatus` (CONNECTED/MISSING/IDLE), and it must match the colour table in the renderer.
`client/ClientLinkView` must stay free of client-only imports, because the payload handler references it on both
sides.

## Gotchas
- This uses CC **internals** (`dan200.computercraft.shared.*`, `core.*`, `impl.Peripherals`) via the full
  `cc-tweaked-1.21.1-forge` jar (`compileOnly`), so CC updates can break it. Re-check mixin targets and
  `ServerContext`/`MethodSupplier` usage when bumping `cct_version`.
- The GameTest server ticks as fast as it can, so tests that boot a real CC computer need huge `timeoutTicks`. Tests
  that change global `Config` values must use their own `batch`, because tests in one batch run at the same time.
- The game test world in `run/world` persists between runs, so `LinkSavedData` (counters, remembered names) carries
  over. Don't assert exact name numbers.
- Computer labels are limited to 32 characters, so Lua-driven tests write full results to `ccwp_result.txt` in the
  computer's folder.
- Stock `peripheral.call` returns `nil` (no error) for unknown names, and the Lua patch keeps that behaviour.
