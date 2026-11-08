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
 * While the player holds a Peripheral Linker, shows the name of each link to the block under the crosshair, coloured
 * like its line. Uses the link view the server already sends for the lines. Client only.
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
        for (var host : ClientLinkView.get().hosts()) {
            for (var link : host.links()) {
                if (!link.pos().equals(pos)) continue;
                graphics.drawCenteredString(mc.font, link.name(), x, y, 0xFF000000 | LinkLineRenderer.statusColour(link.status()));
                y += mc.font.lineHeight + 1;
            }
        }
    }
}
