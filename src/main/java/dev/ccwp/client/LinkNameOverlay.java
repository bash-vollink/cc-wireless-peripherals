package dev.ccwp.client;

import dev.ccwp.CCWirelessPeripherals;
import dev.ccwp.item.PeripheralLinkerItem;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * While the player holds a Peripheral Linker, shows the name of the linked block under the crosshair, coloured like its
 * line. Uses the link view the server already sends for the lines. Client only.
 */
@EventBusSubscriber(modid = CCWirelessPeripherals.MOD_ID, value = Dist.CLIENT)
public final class LinkNameOverlay {
    private LinkNameOverlay() {
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        var mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null || mc.level == null || mc.options.hideGui || mc.screen != null) return;
        if (!PeripheralLinkerItem.isLinker(player.getMainHandItem()) && !PeripheralLinkerItem.isLinker(player.getOffhandItem())) return;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;

        var pos = hit.getBlockPos();
        var graphics = event.getGuiGraphics();
        int x = graphics.guiWidth() / 2;
        int y = graphics.guiHeight() / 2 + 8;
        // Names belong to the block, so every host linking it reports the same name: show it once, in the best status
        // any host has (lowest ordinal: connected, then missing, then idle).
        String name = null;
        byte status = Byte.MAX_VALUE;
        for (var host : ClientLinkView.get().hosts()) {
            for (var link : host.links()) {
                if (!link.pos().equals(pos)) continue;
                name = link.name();
                status = (byte) Math.min(status, link.status());
            }
        }
        if (name != null) graphics.drawCenteredString(mc.font, name, x, y, 0xFF000000 | LinkLineRenderer.statusColour(status));
    }
}
