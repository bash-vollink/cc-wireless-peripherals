package dev.ccwp.test;

import dan200.computercraft.shared.ModRegistry;
import dan200.computercraft.shared.computer.blocks.AbstractComputerBlockEntity;
import dev.ccwp.CCWirelessPeripherals;
import dev.ccwp.data.HostKey;
import dev.ccwp.data.LinkSavedData;
import dev.ccwp.hub.HubScheduler;
import dev.ccwp.hub.PeripheralLookup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.UUID;

/**
 * Dev-only visual smoke test (enabled with {@code -Dccwp.smoke=true}, see the {@code smokeClient} run). Builds a small
 * scene in a singleplayer world, holds a Peripheral Linker, saves a screenshot and quits. Not shipped in the jar.
 */
@EventBusSubscriber(modid = CCWirelessPeripherals.MOD_ID, value = Dist.CLIENT)
public final class ClientSmokeTest {
    private static final boolean ENABLED = Boolean.getBoolean("ccwp.smoke");
    private static int ticks = -1;

    private ClientSmokeTest() {
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (!ENABLED) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
        if (ticks < 0) ticks = 0;
        ticks++;

        var server = mc.getSingleplayerServer();
        var uuid = mc.player.getUUID();
        if (ticks == 40) server.execute(() -> setup(server, uuid));
        if (ticks == 160) {
            mc.options.hideGui = false;
            Screenshot.grab(mc.gameDirectory, "ccwp_smoke.png", mc.getMainRenderTarget(),
                msg -> CCWirelessPeripherals.LOG.info("Smoke screenshot: {}", msg.getString()));
        }
        if (ticks == 180) {
            server.execute(() -> CCWirelessPeripherals.LOG.info("Smoke status: chest={}, broken={}, idle={}",
                status(server, 0), status(server, 1), status(server, 2)));
        }
        if (ticks == 200) mc.stop();
    }

    private static final BlockPos ORIGIN = new BlockPos(0, 120, 0);
    private static final BlockPos COMPUTER = ORIGIN.offset(0, 1, 6);
    private static final BlockPos MODEM = COMPUTER.above();
    private static final BlockPos IDLE_MODEM = ORIGIN.offset(-6, 2, 9);
    private static final BlockPos[] TARGETS = {
        ORIGIN.offset(5, 1, 9),   // chest: connected
        ORIGIN.offset(4, 1, 4),   // barrel, broken after linking: missing
        ORIGIN.offset(-3, 1, 11), // chest on the idle host
    };

    private static String status(MinecraftServer server, int i) {
        var host = new HostKey(server.overworld().dimension(), i == 2 ? IDLE_MODEM : MODEM);
        return HubScheduler.status(host, TARGETS[i]).name();
    }

    private static void setup(MinecraftServer server, UUID uuid) {
        ServerLevel level = server.overworld();
        var player = server.getPlayerList().getPlayer(uuid);
        if (player == null) return;

        level.setDayTime(6000);
        level.setWeatherParameters(6000, 0, false, false);
        for (int x = -10; x <= 10; x++) {
            for (int z = -2; z <= 16; z++) level.setBlockAndUpdate(ORIGIN.offset(x, 0, z), Blocks.SMOOTH_STONE.defaultBlockState());
        }

        level.setBlockAndUpdate(COMPUTER, ModRegistry.Blocks.COMPUTER_ADVANCED.get().defaultBlockState());
        level.setBlockAndUpdate(MODEM, ModRegistry.Blocks.WIRELESS_MODEM_NORMAL.get().defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.DOWN));
        level.setBlockAndUpdate(IDLE_MODEM.below(), Blocks.STONE_BRICKS.defaultBlockState());
        level.setBlockAndUpdate(IDLE_MODEM, ModRegistry.Blocks.WIRELESS_MODEM_ADVANCED.get().defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.DOWN));
        level.setBlockAndUpdate(TARGETS[0], Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(TARGETS[1], Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(TARGETS[2], Blocks.CHEST.defaultBlockState());

        var data = LinkSavedData.get(server);
        var host = new HostKey(level.dimension(), MODEM);
        var idle = new HostKey(level.dimension(), IDLE_MODEM);
        for (int i = 0; i < TARGETS.length; i++) {
            var peripheral = PeripheralLookup.find(level, TARGETS[i], Direction.UP);
            if (peripheral == null) continue;
            data.addLink(i == 2 ? idle : host, data.createLink(level.dimension(), TARGETS[i], Direction.UP, peripheral.getType()));
        }
        level.setBlockAndUpdate(TARGETS[1], Blocks.AIR.defaultBlockState()); // now a "missing" link

        if (level.getBlockEntity(COMPUTER) instanceof AbstractComputerBlockEntity computer) {
            computer.createServerComputer().turnOn();
        }

        var linker = new ItemStack(dev.ccwp.ModRegistry.PERIPHERAL_LINKER.get());
        linker.set(dev.ccwp.ModRegistry.SELECTED_HOST.get(), GlobalPos.of(level.dimension(), MODEM));
        player.setItemInHand(InteractionHand.MAIN_HAND, linker);
        player.teleportTo(level, -1.5, ORIGIN.getY() + 4.5, -3.5, -20f, 25f);
    }
}
