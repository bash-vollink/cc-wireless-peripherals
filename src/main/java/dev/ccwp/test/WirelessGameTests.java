package dev.ccwp.test;

import dan200.computercraft.api.ComputerCraftAPI;
import dan200.computercraft.api.filesystem.Mount;
import dan200.computercraft.api.filesystem.WritableMount;
import dan200.computercraft.api.lua.ILuaContext;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.ObjectArguments;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import dan200.computercraft.api.peripheral.WorkMonitor;
import dan200.computercraft.shared.ModRegistry;
import dan200.computercraft.shared.computer.blocks.AbstractComputerBlockEntity;
import dan200.computercraft.shared.computer.core.ServerContext;
import dev.ccwp.Blacklists;
import dev.ccwp.CCWirelessPeripherals;
import dev.ccwp.Config;
import dev.ccwp.data.HostKey;
import dev.ccwp.data.Link;
import dev.ccwp.data.LinkSavedData;
import dev.ccwp.hub.HubScheduler;
import dev.ccwp.hub.PeripheralLookup;
import dev.ccwp.hub.WirelessHub;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end tests against the real CC: Tweaked peripheral machinery. Run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(CCWirelessPeripherals.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WirelessGameTests {
    private static final ILuaContext NO_MAIN_THREAD = task -> {
        throw new LuaException("main thread tasks not supported in tests");
    };

    /** A computer that just records the events it receives. */
    private static final class FakeComputer implements IComputerAccess {
        final List<List<Object>> events = Collections.synchronizedList(new ArrayList<>());

        boolean hasEvent(String name, Object arg) {
            synchronized (events) {
                for (var e : events) if (e.get(0).equals(name) && e.size() > 1 && Objects.equals(e.get(1), arg)) return true;
            }
            return false;
        }

        @Override
        public @Nullable String mount(String desiredLocation, Mount mount, String driveName) {
            return null;
        }

        @Override
        public @Nullable String mountWritable(String desiredLocation, WritableMount mount, String driveName) {
            return null;
        }

        @Override
        public void unmount(@Nullable String location) {
        }

        @Override
        public int getID() {
            return 0;
        }

        @Override
        public void queueEvent(String event, @Nullable Object... arguments) {
            var list = new ArrayList<>();
            list.add(event);
            if (arguments != null) list.addAll(Arrays.asList(arguments));
            events.add(list);
        }

        @Override
        public String getAttachmentName() {
            return "top";
        }

        @Override
        public Map<String, IPeripheral> getAvailablePeripherals() {
            return Map.of();
        }

        @Override
        public @Nullable IPeripheral getAvailablePeripheral(String name) {
            return null;
        }

        @Override
        public WorkMonitor getMainThreadMonitor() {
            return new WorkMonitor() {
                @Override
                public boolean canWork() {
                    return true;
                }

                @Override
                public boolean shouldWork() {
                    return true;
                }

                @Override
                public void trackWork(long time, TimeUnit unit) {
                }
            };
        }
    }

    private record Setup(IPeripheral modem, FakeComputer computer, HostKey host) {
    }

    private static Setup placeModem(GameTestHelper helper, BlockPos rel, boolean ender) {
        helper.setBlock(rel.below(), Blocks.STONE);
        var block = ender ? ModRegistry.Blocks.WIRELESS_MODEM_ADVANCED.get() : ModRegistry.Blocks.WIRELESS_MODEM_NORMAL.get();
        helper.setBlock(rel, block.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.DOWN));
        var abs = helper.absolutePos(rel);
        var level = helper.getLevel();
        var modem = level.getCapability(PeripheralCapability.get(), abs, Direction.DOWN);
        if (modem == null) throw new net.minecraft.gametest.framework.GameTestAssertException("No modem peripheral");
        var computer = new FakeComputer();
        return new Setup(modem, computer, new HostKey(level.dimension(), abs));
    }

    private static String link(GameTestHelper helper, HostKey host, BlockPos rel) {
        var level = helper.getLevel();
        var abs = helper.absolutePos(rel);
        var peripheral = PeripheralLookup.find(level, abs, Direction.UP);
        if (peripheral == null) throw new net.minecraft.gametest.framework.GameTestAssertException("No peripheral at " + rel);
        var name = WirelessHub.nextName(level.getServer(), peripheral.getType());
        LinkSavedData.get(level.getServer()).addLink(host, new Link(abs, Direction.UP, peripheral.getType(), name));
        HubScheduler.markHostDirty(host);
        return name;
    }

    private static Object[] call(GameTestHelper helper, Setup setup, String method, Object... args) {
        var methods = ServerContext.get(helper.getLevel().getServer()).peripheralMethods().getSelfMethods(setup.modem());
        var fn = methods.get(method);
        if (fn == null) throw new net.minecraft.gametest.framework.GameTestAssertException("Modem has no method " + method + "; has " + methods.keySet());
        try {
            var result = fn.apply(setup.modem(), NO_MAIN_THREAD, setup.computer(), new ObjectArguments(args)).getResult();
            return result == null ? new Object[0] : result;
        } catch (LuaException e) {
            throw new net.minecraft.gametest.framework.GameTestAssertException(method + " failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Collection<String> names(GameTestHelper helper, Setup setup) {
        return (Collection<String>) call(helper, setup, "getNamesRemote")[0];
    }

    @GameTest(template = "empty", timeoutTicks = 200, batch = "ccwp_basic")
    public static void linkedPeripheralsAttachAndDetach(GameTestHelper helper) {
        var setup = placeModem(helper, new BlockPos(1, 1, 1), false);
        helper.setBlock(new BlockPos(5, 1, 1), Blocks.CHEST);
        helper.setBlock(new BlockPos(5, 1, 4), ModRegistry.Blocks.DISK_DRIVE.get());

        var chest = link(helper, setup.host(), new BlockPos(5, 1, 1));
        var drive = link(helper, setup.host(), new BlockPos(5, 1, 4));

        helper.assertTrue(setup.modem().getAdditionalTypes().contains("peripheral_hub"), "modem should be a peripheral_hub");
        setup.modem().attach(setup.computer());

        helper.startSequence()
            .thenWaitUntil(() -> {
                var n = names(helper, setup);
                helper.assertTrue(n.contains(chest) && n.contains(drive), "expected " + chest + " and " + drive + ", got " + n);
            })
            .thenExecute(() -> {
                helper.assertTrue(setup.computer().hasEvent("peripheral", chest), "peripheral event for chest");
                helper.assertTrue(setup.computer().hasEvent("peripheral", drive), "peripheral event for drive");
                helper.assertTrue(Boolean.TRUE.equals(call(helper, setup, "isPresentRemote", drive)[0]), "isPresentRemote");
                helper.assertTrue("drive".equals(call(helper, setup, "getTypeRemote", drive)[0]), "getTypeRemote");
                helper.assertTrue(Boolean.TRUE.equals(call(helper, setup, "hasTypeRemote", chest, "inventory")[0]), "chest is an inventory");
                var methods = (Collection<?>) call(helper, setup, "getMethodsRemote", drive)[0];
                helper.assertTrue(methods.contains("isDiskPresent"), "drive methods: " + methods);
                var present = call(helper, setup, "callRemote", drive, "isDiskPresent");
                helper.assertTrue(Boolean.FALSE.equals(present[0]), "isDiskPresent should be false, got " + Arrays.toString(present));
                helper.assertTrue(HubScheduler.status(setup.host(), helper.absolutePos(new BlockPos(5, 1, 1))) == HubScheduler.LinkStatus.CONNECTED, "status connected");
            })
            // Breaking a linked block detaches it, purely event-driven.
            .thenExecute(() -> helper.setBlock(new BlockPos(5, 1, 4), Blocks.AIR))
            .thenWaitUntil(() -> helper.assertFalse(names(helper, setup).contains(drive), "drive should be gone"))
            .thenExecute(() -> {
                helper.assertTrue(setup.computer().hasEvent("peripheral_detach", drive), "peripheral_detach for drive");
                helper.assertTrue(LinkSavedData.get(helper.getLevel().getServer()).findLink(setup.host(), helper.absolutePos(new BlockPos(5, 1, 4))) == null,
                    "link to the broken drive is removed");
                helper.assertTrue(HubScheduler.pendingCount() == 0, "no pending work once settled");
                setup.modem().detach(setup.computer());
                helper.assertTrue(setup.computer().hasEvent("peripheral_detach", chest), "peripheral_detach for chest on computer detach");
            })
            .thenSucceed();
    }

    // Tests that change global config run in their own batches so they cannot affect each other.
    @GameTest(template = "empty", timeoutTicks = 200, batch = "ccwp_blacklists")
    public static void rangeAndBlacklistsAreEnforced(GameTestHelper helper) {
        var setup = placeModem(helper, new BlockPos(1, 1, 1), false);
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.CHEST);   // distance 2
        helper.setBlock(new BlockPos(8, 1, 1), Blocks.BARREL);  // distance 7
        var near = link(helper, setup.host(), new BlockPos(3, 1, 1));
        var far = link(helper, setup.host(), new BlockPos(8, 1, 1));

        var oldRange = Config.WIRELESS_LINK_RANGE.get();
        var oldPeripheralBlacklist = Config.PERIPHERAL_BLACKLIST.get();
        var oldHostBlacklist = Config.HOST_BLACKLIST.get();
        Runnable restore = () -> {
            Config.WIRELESS_LINK_RANGE.set(oldRange);
            Config.PERIPHERAL_BLACKLIST.set(oldPeripheralBlacklist);
            Config.HOST_BLACKLIST.set(oldHostBlacklist);
            Blacklists.reload();
        };

        Config.WIRELESS_LINK_RANGE.set(4);
        setup.modem().attach(setup.computer());

        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(names(helper, setup).contains(near), "near link attached"))
            .thenExecute(() -> {
                helper.assertFalse(names(helper, setup).contains(far), "far link must be out of range");
                helper.assertTrue(HubScheduler.status(setup.host(), helper.absolutePos(new BlockPos(8, 1, 1))) == HubScheduler.LinkStatus.MISSING, "far link status");
                // Peripheral blacklist by type.
                Config.PERIPHERAL_BLACKLIST.set(List.of("minecraft:chest"));
                Blacklists.reload();
                HubScheduler.markAllDirty();
            })
            .thenWaitUntil(() -> helper.assertFalse(names(helper, setup).contains(near), "blacklisted chest detached"))
            .thenExecute(() -> {
                Config.PERIPHERAL_BLACKLIST.set(List.of());
                Config.HOST_BLACKLIST.set(List.of("computercraft:wireless_modem_normal"));
                Blacklists.reload();
                HubScheduler.markAllDirty();
            })
            .thenWaitUntil(() -> helper.assertTrue(names(helper, setup).isEmpty(), "blacklisted host exposes nothing"))
            .thenExecute(() -> {
                restore.run();
                setup.modem().detach(setup.computer());

                // A player breaking a linked peripheral removes its link, even if it was never attached.
                var level = helper.getLevel();
                var farPos = helper.absolutePos(new BlockPos(8, 1, 1));
                var player = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                    new net.neoforged.neoforge.event.level.BlockEvent.BreakEvent(level, farPos, level.getBlockState(farPos), player));
                var data = LinkSavedData.get(level.getServer());
                helper.assertTrue(data.findLink(setup.host(), farPos) == null, "broken peripheral's link removed");
                helper.assertTrue(data.findLink(setup.host(), helper.absolutePos(new BlockPos(3, 1, 1))) != null, "other link kept");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 200, batch = "ccwp_ender")
    public static void enderModemUsesEnderRange(GameTestHelper helper) {
        var setup = placeModem(helper, new BlockPos(1, 1, 1), true);
        helper.setBlock(new BlockPos(8, 1, 8), Blocks.CHEST); // ~9.9 blocks away
        var name = link(helper, setup.host(), new BlockPos(8, 1, 8));

        var oldWireless = Config.WIRELESS_LINK_RANGE.get();
        Config.WIRELESS_LINK_RANGE.set(4); // must not apply to ender modems
        setup.modem().attach(setup.computer());

        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(names(helper, setup).contains(name), "ender modem reaches further"))
            .thenExecute(() -> {
                Config.WIRELESS_LINK_RANGE.set(oldWireless);
                setup.modem().detach(setup.computer());
            })
            .thenSucceed();
    }

    /** Lua program run on a real CC computer, using only the stock peripheral API. */
    private static final String LUA_PROGRAM = """
        local name = "$NAME"
        for _ = 1, 50 do
          if peripheral.isPresent(name) then break end
          sleep(0.1)
        end
        local ok, err = pcall(function()
          assert(peripheral.getType("top") == "modem", "top is " .. tostring(peripheral.getType("top")))
          assert(peripheral.hasType("top", "peripheral_hub"), "modem is not a peripheral_hub")
          local found = false
          for _, n in ipairs(peripheral.getNames()) do if n == name then found = true end end
          assert(found, "not in getNames: " .. table.concat(peripheral.getNames(), ","))
          local chest = assert(peripheral.wrap(name), "wrap failed")
          assert(chest.size() == 27, "size() = " .. tostring(chest.size()))
          assert(peripheral.getName(chest) == name, "getName")
          assert(peripheral.hasType(name, "inventory"), "hasType inventory")
          local first = peripheral.find("inventory")
          assert(first and peripheral.getName(first) == name, "find failed")
          local modem = peripheral.wrap("top")
          assert(modem.isWireless(), "isWireless")
          assert(#modem.getNamesRemote() == 1, "getNamesRemote")
          modem.open(42)
          assert(modem.isOpen(42), "channels still work")
          modem.close(42)
        end)
        local f = fs.open("ccwp_result.txt", "w")
        f.write(ok and "ok" or tostring(err))
        f.close()
        os.setComputerLabel(ok and "ccwp_ok" or "ccwp_fail")
        """;

    // The test server ticks as fast as it can while the computer boots in real time, so allow plenty of ticks.
    @GameTest(template = "empty", timeoutTicks = 72000, batch = "ccwp_lua")
    public static void luaPeripheralApiSeesLinkedPeripherals(GameTestHelper helper) {
        var level = helper.getLevel();
        var computerRel = new BlockPos(1, 1, 1);
        var modemRel = computerRel.above();

        helper.setBlock(computerRel, ModRegistry.Blocks.COMPUTER_NORMAL.get());
        helper.setBlock(modemRel, ModRegistry.Blocks.WIRELESS_MODEM_NORMAL.get().defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.DOWN));
        helper.setBlock(new BlockPos(5, 1, 3), Blocks.CHEST);

        var host = new HostKey(level.dimension(), helper.absolutePos(modemRel));
        var name = link(helper, host, new BlockPos(5, 1, 3));

        runLuaAndExpectOk(helper, computerRel, LUA_PROGRAM.replace("$NAME", name));
    }

    /** Boot the computer at {@code rel} with {@code program} as startup and succeed once it labels itself "ccwp_ok". */
    private static void runLuaAndExpectOk(GameTestHelper helper, BlockPos rel, String program) {
        var server = helper.getLevel().getServer();
        var id = ComputerCraftAPI.createUniqueNumberedSaveDir(server, "computer");
        var dir = ServerContext.get(server).storageDir().resolve("computer").resolve(Integer.toString(id));
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("startup.lua"), program);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(rel)) instanceof AbstractComputerBlockEntity computer)) {
            throw new net.minecraft.gametest.framework.GameTestAssertException("No computer block entity at " + rel);
        }
        computer.setComputerID(id);
        computer.createServerComputer().turnOn();

        helper.succeedWhen(() -> {
            var sc = computer.getServerComputer();
            var label = sc == null ? null : sc.getLabel();
            if ("ccwp_fail".equals(label)) {
                String detail;
                try {
                    detail = Files.readString(dir.resolve("ccwp_result.txt"));
                } catch (IOException e) {
                    detail = "(no result file: " + e + ")";
                }
                throw new net.minecraft.gametest.framework.GameTestAssertException("Lua failed: " + detail);
            }
            helper.assertTrue("ccwp_ok".equals(label), "Lua has not finished (label " + label + ")");
        });
    }

    /** Lua run on a CC: Modern server (built-in wireless modem). */
    private static final String LUA_SERVER_PROGRAM = """
        local name = "$NAME"
        for _ = 1, 50 do
          if peripheral.isPresent(name) then break end
          sleep(0.1)
        end
        local ok, err = pcall(function()
          local found = false
          for _, n in ipairs(peripheral.getNames()) do if n == name then found = true end end
          assert(found, "not in getNames: " .. table.concat(peripheral.getNames(), ","))
          -- CC: Modern 0.1.1 puts the server modem on a fixed side; newer versions nest it in an "internal" hub.
          local modem_name
          for _, n in ipairs(peripheral.getNames()) do
            if peripheral.hasType(n, "modem") and peripheral.call(n, "isWireless") then modem_name = n end
          end
          assert(modem_name, "server has no wireless modem: " .. table.concat(peripheral.getNames(), ","))
          assert(peripheral.hasType(modem_name, "peripheral_hub"), "server modem " .. modem_name .. " is not a peripheral_hub")
          local chest = assert(peripheral.wrap(name), "wrap failed")
          assert(chest.size() == 27, "size() = " .. tostring(chest.size()))
          assert(peripheral.getType(name) == "minecraft:chest", "getType")
          local any = false
          for _, p in ipairs({ peripheral.find("inventory") }) do
            if peripheral.getName(p) == name then any = true end
          end
          assert(any, "find failed")
        end)
        local f = fs.open("ccwp_result.txt", "w")
        f.write(ok and "ok" or tostring(err))
        f.close()
        os.setComputerLabel(ok and "ccwp_ok" or "ccwp_fail")
        """;

    @GameTest(template = "empty", timeoutTicks = 72000, batch = "ccwp_cc_modern")
    public static void ccModernServerSeesLinkedPeripherals(GameTestHelper helper) {
        if (!net.neoforged.fml.ModList.get().isLoaded("cc_modern")) {
            CCWirelessPeripherals.LOG.info("CC: Modern not installed; skipping server test");
            helper.succeed();
            return;
        }
        var serverBlock = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("cc_modern", "server"));
        var serverRel = new BlockPos(1, 1, 1);
        helper.setBlock(serverRel, serverBlock);
        helper.setBlock(new BlockPos(6, 1, 3), Blocks.CHEST);

        var host = new HostKey(helper.getLevel().dimension(), helper.absolutePos(serverRel));
        helper.assertTrue(Blacklists.isHost(helper.getLevel().getBlockState(host.pos())), "cc_modern:server is a host");
        var name = link(helper, host, new BlockPos(6, 1, 3));

        runLuaAndExpectOk(helper, serverRel, LUA_SERVER_PROGRAM.replace("$NAME", name));
    }
}
