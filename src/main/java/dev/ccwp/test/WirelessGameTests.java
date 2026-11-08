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
import dan200.computercraft.api.pocket.IPocketUpgrade;
import dan200.computercraft.api.turtle.ITurtleUpgrade;
import dan200.computercraft.api.turtle.TurtleSide;
import dan200.computercraft.api.upgrades.UpgradeData;
import dan200.computercraft.shared.peripheral.modem.wireless.WirelessModemPeripheral;
import dan200.computercraft.shared.pocket.items.PocketComputerItem;
import dan200.computercraft.shared.turtle.blocks.TurtleBlockEntity;
import dan200.computercraft.shared.util.NonNegativeId;
import dev.ccwp.Blacklists;
import dev.ccwp.CCWirelessPeripherals;
import dev.ccwp.Config;
import dev.ccwp.data.HostKey;
import dev.ccwp.data.LinkNames;
import dev.ccwp.data.LinkSavedData;
import dev.ccwp.hub.HubScheduler;
import dev.ccwp.hub.MobileHosts;
import dev.ccwp.hub.PeripheralLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
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
        final int id;

        FakeComputer() {
            this(0);
        }

        FakeComputer(int id) {
            this.id = id;
        }

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
            return id;
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
        return new Setup(modem, computer, new HostKey.Placed(level.dimension(), abs));
    }

    private static LinkSavedData data(GameTestHelper helper) {
        return LinkSavedData.get(helper.getLevel().getServer());
    }

    private static GlobalPos target(GameTestHelper helper, BlockPos rel) {
        return GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(rel));
    }

    /** Link the block at {@code rel} (looked up from above) and return its name. */
    private static String link(GameTestHelper helper, HostKey host, BlockPos rel) {
        var level = helper.getLevel();
        var abs = helper.absolutePos(rel);
        var peripheral = PeripheralLookup.find(level, abs, Direction.UP);
        if (peripheral == null) throw new net.minecraft.gametest.framework.GameTestAssertException("No peripheral at " + rel);
        var data = data(helper);
        var target = target(helper, rel);
        data.addLink(host, target, Direction.UP, peripheral.getType());
        HubScheduler.markHostsDirty(data.hostsLinking(target));
        return data.nameOf(target);
    }

    private static void unlink(GameTestHelper helper, HostKey host, BlockPos rel) {
        data(helper).removeLink(host, target(helper, rel));
        HubScheduler.markHostDirty(host);
    }

    private static String nameAt(GameTestHelper helper, BlockPos rel) {
        return data(helper).nameOf(target(helper, rel));
    }

    private static @Nullable String renameError(GameTestHelper helper, BlockPos rel, @Nullable String name) {
        var data = data(helper);
        var target = target(helper, rel);
        var result = data.rename(target, name);
        HubScheduler.markHostsDirty(data.hostsLinking(target));
        return result.error();
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
                helper.assertTrue(HubScheduler.status(setup.host(), target(helper, new BlockPos(5, 1, 1))) == HubScheduler.LinkStatus.CONNECTED, "status connected");
            })
            // Breaking a linked block detaches it, purely event-driven.
            .thenExecute(() -> helper.setBlock(new BlockPos(5, 1, 4), Blocks.AIR))
            .thenWaitUntil(() -> helper.assertFalse(names(helper, setup).contains(drive), "drive should be gone"))
            .thenExecute(() -> {
                helper.assertTrue(setup.computer().hasEvent("peripheral_detach", drive), "peripheral_detach for drive");
                helper.assertTrue(data(helper).findLink(setup.host(), target(helper, new BlockPos(5, 1, 4))) == null,
                    "link to the broken drive is removed");
                helper.assertTrue(HubScheduler.pendingCount() == 0, "no pending work once settled");
                setup.modem().detach(setup.computer());
                helper.assertTrue(setup.computer().hasEvent("peripheral_detach", chest), "peripheral_detach for chest on computer detach");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 200, batch = "ccwp_names")
    public static void linkNamesAreStableAndRenamable(GameTestHelper helper) {
        var setup = placeModem(helper, new BlockPos(1, 1, 1), false);
        var chestPos = new BlockPos(4, 1, 1);
        var otherPos = new BlockPos(4, 1, 4);
        helper.setBlock(chestPos, Blocks.CHEST);
        helper.setBlock(otherPos, Blocks.BARREL);

        var auto = link(helper, setup.host(), chestPos);
        var other = link(helper, setup.host(), otherPos);
        helper.assertTrue(LinkNames.parseAuto("minecraft:chest", auto) >= 0, "automatic name format: " + auto);
        helper.assertFalse(data(helper).isCustomName(target(helper, chestPos)), "automatic name is not custom");

        // Unlinking and relinking the same block keeps its name.
        unlink(helper, setup.host(), chestPos);
        helper.assertTrue(auto.equals(data(helper).rememberedName(target(helper, chestPos))), "name remembered while unlinked");
        helper.assertTrue(auto.equals(link(helper, setup.host(), chestPos)), "relinked chest keeps " + auto);

        // Invalid custom names are refused.
        for (var bad : List.of("top", "storage_3", "storage_w3", "has space", "", "x".repeat(LinkNames.MAX_LENGTH + 1), other)) {
            helper.assertTrue(renameError(helper, chestPos, bad) != null, "name should be refused: '" + bad + "'");
        }
        helper.assertTrue(auto.equals(nameAt(helper, chestPos)), "refused rename changes nothing");

        setup.modem().attach(setup.computer());
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(names(helper, setup).contains(auto), "auto name attached"))
            .thenExecute(() -> helper.assertTrue(renameError(helper, chestPos, "storage") == null, "rename accepted"))
            .thenWaitUntil(() -> {
                var n = names(helper, setup);
                helper.assertTrue(n.contains("storage") && !n.contains(auto), "renamed: " + n);
            })
            .thenExecute(() -> {
                helper.assertTrue(setup.computer().hasEvent("peripheral_detach", auto), "detach event for old name");
                helper.assertTrue(setup.computer().hasEvent("peripheral", "storage"), "attach event for new name");
                helper.assertTrue(data(helper).isCustomName(target(helper, chestPos)), "custom flag set");

                // A custom name also survives unlink + relink...
                unlink(helper, setup.host(), chestPos);
                helper.assertTrue("storage".equals(link(helper, setup.host(), chestPos)), "relinked chest keeps custom name");
                // ...and a type change.
                helper.setBlock(chestPos, Blocks.BARREL);
            })
            .thenWaitUntil(() -> helper.assertTrue("minecraft:barrel".equals(data(helper).typeOf(target(helper, chestPos))), "type updated"))
            .thenExecute(() -> {
                helper.assertTrue("storage".equals(nameAt(helper, chestPos)), "custom name kept on type change");
                // Resetting gives an automatic name for the current type.
                helper.assertTrue(renameError(helper, chestPos, null) == null, "reset accepted");
                var reset = nameAt(helper, chestPos);
                helper.assertTrue(!data(helper).isCustomName(target(helper, chestPos)) && LinkNames.parseAuto("minecraft:barrel", reset) >= 0, "reset name: " + reset);
            })
            .thenWaitUntil(() -> helper.assertTrue(names(helper, setup).contains(nameAt(helper, chestPos)), "reset name attached"))
            .thenExecute(() -> {
                // An automatic name is replaced when the type changes.
                helper.assertTrue(other.equals(nameAt(helper, otherPos)), "barrel name before type change");
                helper.setBlock(otherPos, Blocks.CHEST);
            })
            .thenWaitUntil(() -> helper.assertTrue(LinkNames.parseAuto("minecraft:chest", nameAt(helper, otherPos)) >= 0,
                "auto name follows type: " + nameAt(helper, otherPos)))
            .thenExecute(() -> setup.modem().detach(setup.computer()))
            .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 200, batch = "ccwp_shared_names")
    public static void namesAreSharedByAllHosts(GameTestHelper helper) {
        var a = placeModem(helper, new BlockPos(1, 1, 1), false);
        var b = placeModem(helper, new BlockPos(1, 1, 8), false);
        var chestPos = new BlockPos(4, 1, 4);
        helper.setBlock(chestPos, Blocks.CHEST);

        var nameA = link(helper, a.host(), chestPos);
        var nameB = link(helper, b.host(), chestPos);
        helper.assertTrue(nameA.equals(nameB), "one name per block: " + nameA + " vs " + nameB);

        a.modem().attach(a.computer());
        b.modem().attach(b.computer());
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(names(helper, a).contains(nameA) && names(helper, b).contains(nameA), "both hosts expose " + nameA))
            .thenExecute(() -> helper.assertTrue(renameError(helper, chestPos, "shared_chest") == null, "rename accepted"))
            .thenWaitUntil(() -> {
                var na = names(helper, a);
                var nb = names(helper, b);
                helper.assertTrue(na.contains("shared_chest") && nb.contains("shared_chest") && !na.contains(nameA) && !nb.contains(nameA),
                    "rename applies to every host: " + na + " / " + nb);
            })
            .thenExecute(() -> {
                helper.assertTrue(a.computer().hasEvent("peripheral", "shared_chest") && b.computer().hasEvent("peripheral", "shared_chest"),
                    "both computers see the new name");
                // Unlinking from one host leaves the other's link and the name alone.
                unlink(helper, a.host(), chestPos);
                helper.assertTrue("shared_chest".equals(nameAt(helper, chestPos)), "name kept while another host links it");
                helper.assertTrue(data(helper).rememberedName(target(helper, chestPos)) == null, "still linked, so not just remembered");
            })
            .thenWaitUntil(() -> helper.assertFalse(names(helper, a).contains("shared_chest"), "unlinked from a"))
            .thenExecute(() -> {
                helper.assertTrue(names(helper, b).contains("shared_chest"), "b still has it");
                helper.assertTrue("shared_chest".equals(link(helper, a.host(), chestPos)), "relinking a uses the shared name");
            })
            .thenWaitUntil(() -> helper.assertTrue(names(helper, a).contains("shared_chest"), "a has it again"))
            .thenExecute(() -> {
                a.modem().detach(a.computer());
                b.modem().detach(b.computer());
                // The test world persists: unlink, so the custom name is only remembered and the next run can take it.
                data(helper).removeHost(a.host());
                data(helper).removeHost(b.host());
            })
            .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 200, batch = "ccwp_name_reuse")
    public static void freedNamesAreReusedWhenEnabled(GameTestHelper helper) {
        var setup = placeModem(helper, new BlockPos(1, 1, 1), false);
        var a = new BlockPos(4, 1, 1);
        var b = new BlockPos(4, 1, 4);
        var c = new BlockPos(6, 1, 2);
        helper.setBlock(a, Blocks.FURNACE);
        helper.setBlock(b, Blocks.FURNACE);
        helper.setBlock(c, Blocks.FURNACE);

        var old = Config.REUSE_FREED_NAMES.get();
        try {
            Config.REUSE_FREED_NAMES.set(false);
            var first = link(helper, setup.host(), a);
            var firstId = LinkNames.parseAuto("minecraft:furnace", first);
            unlink(helper, setup.host(), a);
            var second = link(helper, setup.host(), b);
            helper.assertTrue(LinkNames.parseAuto("minecraft:furnace", second) > firstId, "no reuse when disabled: " + first + " then " + second);
            unlink(helper, setup.host(), b);
            helper.assertTrue(first.equals(link(helper, setup.host(), a)), "block a still gets its own name back");
            unlink(helper, setup.host(), a);

            // A block's own remembered name still wins over the pool.
            Config.REUSE_FREED_NAMES.set(true);
            helper.assertTrue(second.equals(link(helper, setup.host(), b)), "block b gets its own name back");
            // A new block takes the lowest freed number (it may be lower than ours if another test freed one).
            var reused = link(helper, setup.host(), c);
            helper.assertTrue(LinkNames.parseAuto("minecraft:furnace", reused) <= firstId, "freed number reused: got " + reused + ", freed " + first);
            var data = data(helper);
            helper.assertFalse(reused.equals(data.rememberedName(target(helper, a))), "a reused name is no longer remembered for its old block");

            // Naming state survives a save/load round trip.
            var registries = helper.getLevel().registryAccess();
            var copy = LinkSavedData.load(data.save(new CompoundTag(), registries), registries);
            helper.assertTrue(copy.isLive(reused) && copy.isLive(second), "live names restored");
            helper.assertTrue(copy.findLink(setup.host(), target(helper, c)) != null && reused.equals(copy.nameOf(target(helper, c))), "links restored");
            unlink(helper, setup.host(), c);
            var reloaded = LinkSavedData.load(data.save(new CompoundTag(), registries), registries);
            helper.assertTrue(reused.equals(reloaded.rememberedName(target(helper, c))), "remembered names restored");
            var freshTarget = target(helper, new BlockPos(6, 1, 6));
            reloaded.addLink(setup.host(), freshTarget, Direction.UP, "minecraft:furnace");
            var fresh = reloaded.nameOf(freshTarget);
            helper.assertFalse(fresh.equals(second), "counter/pool restored without clashes: " + fresh);
        } finally {
            Config.REUSE_FREED_NAMES.set(old);
        }
        helper.succeed();
    }

    /** Saves from before per-block names stored a name per link; merging keeps one name per block. */
    @GameTest(template = "empty", timeoutTicks = 20, batch = "ccwp_legacy")
    public static void legacyPerLinkNamesAreMerged(GameTestHelper helper) {
        var dim = helper.getLevel().dimension();
        var host1 = new BlockPos(1000, 64, 1000);
        var host2 = new BlockPos(1000, 64, 1010);
        var p = new BlockPos(1001, 64, 1005);
        var q = new BlockPos(1002, 64, 1005);
        var r = new BlockPos(1003, 64, 1005);

        var hosts = new ListTag();
        hosts.add(legacyHost(dim, host1, legacyLink(p, "minecraft:chest_w900", false), legacyLink(q, "minecraft:chest_w903", false)));
        hosts.add(legacyHost(dim, host2, legacyLink(p, "minecraft:chest_w901", false), legacyLink(q, "vault", true)));
        var remembered = new ListTag();
        var rTag = new CompoundTag();
        rTag.put("block", GlobalPos.CODEC.encodeStart(NbtOps.INSTANCE, GlobalPos.of(dim, r)).getOrThrow());
        rTag.putString("type", "minecraft:chest");
        rTag.putString("name", "minecraft:chest_w902");
        remembered.add(rTag);
        var root = new CompoundTag();
        root.put("hosts", hosts);
        root.put("remembered", remembered);

        var registries = helper.getLevel().registryAccess();
        var data = LinkSavedData.load(root, registries);
        for (var copy : List.of(data, LinkSavedData.load(data.save(new CompoundTag(), registries), registries))) {
            helper.assertTrue("minecraft:chest_w900".equals(copy.nameOf(GlobalPos.of(dim, p))), "first name wins: " + copy.nameOf(GlobalPos.of(dim, p)));
            helper.assertTrue("vault".equals(copy.nameOf(GlobalPos.of(dim, q))), "custom name wins: " + copy.nameOf(GlobalPos.of(dim, q)));
            helper.assertTrue(copy.isCustomName(GlobalPos.of(dim, q)), "custom flag kept");
            helper.assertFalse(copy.isLive("minecraft:chest_w901") || copy.isLive("minecraft:chest_w903"), "dropped names are free");
            helper.assertTrue("minecraft:chest_w902".equals(copy.rememberedName(GlobalPos.of(dim, r))), "remembered names kept");
            helper.assertTrue(copy.getLinks(new HostKey.Placed(dim, host1)).size() == 2 && copy.getLinks(new HostKey.Placed(dim, host2)).size() == 2,
                "all links kept");
            helper.assertTrue("minecraft:chest".equals(copy.typeOf(GlobalPos.of(dim, p))), "type kept");
        }
        helper.succeed();
    }

    private static CompoundTag legacyHost(ResourceKey<Level> dim, BlockPos pos, CompoundTag... links) {
        var host = new CompoundTag();
        host.putString("dimension", dim.location().toString());
        host.put("pos", BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, pos).getOrThrow());
        var list = new ListTag();
        list.addAll(Arrays.asList(links));
        var entry = new CompoundTag();
        entry.put("host", host);
        entry.put("links", list);
        return entry;
    }

    private static CompoundTag legacyLink(BlockPos pos, String name, boolean custom) {
        var link = new CompoundTag();
        link.put("pos", BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, pos).getOrThrow());
        link.putString("face", "up");
        link.putString("type", "minecraft:chest");
        link.putString("name", name);
        if (custom) link.putBoolean("custom", true);
        return link;
    }

    private static <T extends dan200.computercraft.api.upgrades.UpgradeBase> UpgradeData<T> upgrade(
        GameTestHelper helper, ResourceKey<net.minecraft.core.Registry<T>> registry, String id) {
        var holder = helper.getLevel().registryAccess().registryOrThrow(registry)
            .getHolderOrThrow(ResourceKey.create(registry, ResourceLocation.fromNamespaceAndPath("computercraft", id)));
        return UpgradeData.ofDefault(holder);
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "ccwp_pocket")
    public static void pocketComputersNeedAnEnderModemAndAnId(GameTestHelper helper) {
        var pocket = new ItemStack(ModRegistry.Items.POCKET_COMPUTER_NORMAL.get());
        helper.assertTrue(MobileHosts.checkPocket(pocket).key() == null, "no modem: refused");

        PocketComputerItem.setUpgrade(pocket, upgrade(helper, IPocketUpgrade.REGISTRY, "wireless_modem_normal"));
        pocket.set(ModRegistry.DataComponents.COMPUTER_ID.get(), NonNegativeId.of(4242));
        helper.assertTrue("message.ccwirelessperipherals.mobile_needs_ender".equals(MobileHosts.checkPocket(pocket).error()), "normal modem: refused");

        PocketComputerItem.setUpgrade(pocket, upgrade(helper, IPocketUpgrade.REGISTRY, "wireless_modem_advanced"));
        var check = MobileHosts.checkPocket(pocket);
        helper.assertTrue(check.key() != null && check.key().computerId() == 4242, "ender modem: host keyed by computer id");

        pocket.remove(ModRegistry.DataComponents.COMPUTER_ID.get());
        helper.assertTrue("message.ccwirelessperipherals.mobile_no_id".equals(MobileHosts.checkPocket(pocket).error()), "never turned on: refused");
        helper.succeed();
    }

    /** Place a turtle with a modem on its left and give it a computer id. */
    private static TurtleBlockEntity placeTurtle(GameTestHelper helper, BlockPos rel, boolean ender) {
        helper.setBlock(rel, ModRegistry.Blocks.TURTLE_NORMAL.get());
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(rel)) instanceof TurtleBlockEntity turtle)) {
            throw new net.minecraft.gametest.framework.GameTestAssertException("No turtle at " + rel);
        }
        turtle.getAccess().setUpgrade(TurtleSide.LEFT, upgrade(helper, ITurtleUpgrade.REGISTRY, ender ? "wireless_modem_advanced" : "wireless_modem_normal"));
        turtle.createServerComputer(); // assigns the computer id; the computer stays off
        return turtle;
    }

    @GameTest(template = "empty", timeoutTicks = 400, batch = "ccwp_mobile")
    public static void turtleWithEnderModemHostsWithoutRangeLimit(GameTestHelper helper) {
        var turtle = placeTurtle(helper, new BlockPos(1, 1, 1), true);
        var plain = placeTurtle(helper, new BlockPos(8, 1, 1), false);
        var check = MobileHosts.checkTurtle(turtle);
        helper.assertTrue(check.key() != null && check.key().computerId() == turtle.getComputerID(), "turtle keyed by computer id: " + check.error());
        helper.assertTrue("message.ccwirelessperipherals.mobile_needs_ender".equals(MobileHosts.checkTurtle(plain).error()), "normal modem turtle refused");
        HostKey key = check.key();
        HostKey plainKey = new HostKey.Mobile(plain.getComputerID());

        var chestPos = new BlockPos(1, 1, 8); // 7 blocks away
        helper.setBlock(chestPos, Blocks.CHEST);
        var chest = link(helper, key, chestPos);
        link(helper, plainKey, chestPos); // stored, but a normal modem can't host
        var netherTarget = GlobalPos.of(Level.NETHER, new BlockPos(0, 64, 0));
        data(helper).addLink(key, netherTarget, Direction.UP, "minecraft:chest");
        var netherName = data(helper).nameOf(netherTarget);

        // Range settings never apply to mobile hosts.
        var oldEnder = Config.ENDER_LINK_RANGE.get();
        var oldWireless = Config.WIRELESS_LINK_RANGE.get();
        Config.ENDER_LINK_RANGE.set(2);
        Config.WIRELESS_LINK_RANGE.set(2);
        Runnable restore = () -> {
            Config.ENDER_LINK_RANGE.set(oldEnder);
            Config.WIRELESS_LINK_RANGE.set(oldWireless);
        };

        var setups = new Setup[2];
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(turtle.getAccess().getPeripheral(TurtleSide.LEFT) instanceof WirelessModemPeripheral
                && plain.getAccess().getPeripheral(TurtleSide.LEFT) instanceof WirelessModemPeripheral, "turtle modems created"))
            .thenExecute(() -> {
                setups[0] = new Setup(turtle.getAccess().getPeripheral(TurtleSide.LEFT), new FakeComputer(turtle.getComputerID()), key);
                setups[1] = new Setup(plain.getAccess().getPeripheral(TurtleSide.LEFT), new FakeComputer(plain.getComputerID()), plainKey);
                for (var setup : setups) setup.modem().attach(setup.computer());
            })
            .thenWaitUntil(() -> helper.assertTrue(names(helper, setups[0]).contains(chest), "mobile host reaches past the range settings"))
            .thenExecute(() -> {
                helper.assertFalse(names(helper, setups[0]).contains(netherName), "links in another dimension are unreachable");
                helper.assertTrue(HubScheduler.status(key, netherTarget) == HubScheduler.LinkStatus.MISSING, "other-dimension link is missing");
                helper.assertTrue(HubScheduler.status(key, target(helper, chestPos)) == HubScheduler.LinkStatus.CONNECTED, "chest connected");
                helper.assertTrue(names(helper, setups[1]).isEmpty(), "a normal modem turtle exposes nothing");

                // Links follow the turtle's computer id, not its position.
                helper.assertTrue(turtle.getAccess().teleportTo(helper.getLevel(), helper.absolutePos(new BlockPos(3, 1, 3))), "turtle moved");
                HubScheduler.markHostDirty(key);
            })
            .thenIdle(2)
            .thenExecute(() -> {
                helper.assertTrue(names(helper, setups[0]).contains(chest), "still linked after moving");
                helper.assertTrue(data(helper).getLinks(key).size() == 2, "links kept");
                for (var setup : setups) setup.modem().detach(setup.computer());
                restore.run();
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
                helper.assertTrue(HubScheduler.status(setup.host(), target(helper, new BlockPos(8, 1, 1))) == HubScheduler.LinkStatus.MISSING, "far link status");
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
                helper.assertTrue(data.findLink(setup.host(), target(helper, new BlockPos(8, 1, 1))) == null, "broken peripheral's link removed");
                helper.assertTrue(data.findLink(setup.host(), target(helper, new BlockPos(3, 1, 1))) != null, "other link kept");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 200, batch = "ccwp_ender")
    public static void enderModemRangeIsUnlimitedByDefault(GameTestHelper helper) {
        var setup = placeModem(helper, new BlockPos(1, 1, 1), true);
        helper.setBlock(new BlockPos(8, 1, 8), Blocks.CHEST); // ~9.9 blocks away
        var name = link(helper, setup.host(), new BlockPos(8, 1, 8));
        // The world's config file may predate the new default, so check the spec and then use it.
        helper.assertTrue(Config.ENDER_LINK_RANGE.getDefault() == 0, "ender range is unlimited by default");
        var oldWireless = Config.WIRELESS_LINK_RANGE.get();
        var oldEnder = Config.ENDER_LINK_RANGE.get();
        Config.ENDER_LINK_RANGE.set(0);
        var enderState = helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(1, 1, 1)));
        helper.assertTrue(Blacklists.rangeFor(enderState) == 0, "0 = unlimited");
        Config.WIRELESS_LINK_RANGE.set(4); // must not apply to ender modems
        setup.modem().attach(setup.computer());

        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(names(helper, setup).contains(name), "ender modem reaches further"))
            .thenExecute(() -> {
                // A lowered ender range still applies to placed hosts.
                Config.ENDER_LINK_RANGE.set(4);
                HubScheduler.markAllDirty();
            })
            .thenWaitUntil(() -> helper.assertFalse(names(helper, setup).contains(name), "lowered ender range applies"))
            .thenExecute(() -> {
                Config.WIRELESS_LINK_RANGE.set(oldWireless);
                Config.ENDER_LINK_RANGE.set(oldEnder);
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

        var host = new HostKey.Placed(level.dimension(), helper.absolutePos(modemRel));
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

        var host = new HostKey.Placed(helper.getLevel().dimension(), helper.absolutePos(serverRel));
        helper.assertTrue(Blacklists.isHost(helper.getLevel().getBlockState(host.pos())), "cc_modern:server is a host");
        var name = link(helper, host, new BlockPos(6, 1, 3));

        runLuaAndExpectOk(helper, serverRel, LUA_SERVER_PROGRAM.replace("$NAME", name));
    }
}
