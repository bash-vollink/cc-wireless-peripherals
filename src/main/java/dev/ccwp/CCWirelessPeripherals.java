package dev.ccwp;

import dan200.computercraft.api.ComputerCraftAPI;
import dev.ccwp.command.CcwpCommand;
import dev.ccwp.data.HostKey;
import dev.ccwp.data.LinkSavedData;
import dev.ccwp.hub.HubScheduler;
import dev.ccwp.hub.WirelessHubMethods;
import dev.ccwp.net.LinkViewPayload;
import dev.ccwp.net.LinkViewSync;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(CCWirelessPeripherals.MOD_ID)
public final class CCWirelessPeripherals {
    public static final String MOD_ID = "ccwirelessperipherals";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public CCWirelessPeripherals(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, Config.SERVER_SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC);

        ModRegistry.register(modBus);
        modBus.addListener(this::commonSetup);
        modBus.addListener(this::registerPayloads);
        modBus.addListener(this::onConfigLoad);
        modBus.addListener(this::onConfigReload);

        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::onBlockBreak);
        NeoForge.EVENT_BUS.addListener(this::onEntityJoinLevel);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        ComputerCraftAPI.registerGenericSource(new WirelessHubMethods());
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").optional().playToClient(LinkViewPayload.TYPE, LinkViewPayload.STREAM_CODEC, LinkViewPayload::handle);
    }

    private void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == Config.SERVER_SPEC) Blacklists.reload();
    }

    private void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == Config.SERVER_SPEC) {
            Blacklists.reload();
            HubScheduler.markAllDirty();
        }
    }

    private void onServerTick(ServerTickEvent.Post event) {
        var server = event.getServer();
        HubScheduler.tick(server);
        LinkViewSync.tick(server);
    }

    private void onServerStopped(ServerStoppedEvent event) {
        HubScheduler.clear();
        LinkViewSync.clear();
    }

    /**
     * Breaking a host drops its links, like breaking a wired modem drops its connections; breaking a linked
     * peripheral drops the links to it. (Non-player removals are caught when the host next refreshes.)
     */
    private void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) return;
        var data = LinkSavedData.get(level.getServer());
        if (Blacklists.isHostType(event.getState())) {
            var key = new HostKey.Placed(level.dimension(), event.getPos());
            if (data.removeHost(key)) HubScheduler.markHostDirty(key);
        }
        HubScheduler.markHostsDirty(data.removeLinksTo(GlobalPos.of(level.dimension(), event.getPos())));
    }

    /**
     * A player (or a dropped item) arriving in a level may have carried a pocket computer to another dimension, where
     * its links in the old dimension are unreachable. Checking costs one comparison per running mobile host.
     */
    private void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (event.getEntity() instanceof Player || event.getEntity() instanceof ItemEntity) HubScheduler.checkMobileDimensions();
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        CcwpCommand.register(event.getDispatcher());
    }
}
