package dev.ccwp.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ccwp.CCWirelessPeripherals;
import dev.ccwp.Config;
import dev.ccwp.ModRegistry;
import dev.ccwp.hub.HubScheduler;
import dev.ccwp.item.PeripheralLinkerItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Draws host → peripheral lines while the player holds a Peripheral Linker. Client only. */
@EventBusSubscriber(modid = CCWirelessPeripherals.MOD_ID, value = Dist.CLIENT)
public final class LinkLineRenderer {
    private static final int[] STATUS_COLOURS = {
        0x40FF40, // CONNECTED: green
        0xFF4040, // MISSING: red
        0xFFD040, // IDLE: yellow (no computer is using the host)
    };

    private LinkLineRenderer() {
    }

    static int statusColour(byte status) {
        return STATUS_COLOURS[Math.min(Math.max(status, 0), STATUS_COLOURS.length - 1)];
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientLinkView.set(dev.ccwp.net.LinkViewPayload.EMPTY);
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        var mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null || mc.level == null) return;

        var main = player.getMainHandItem();
        var off = player.getOffhandItem();
        GlobalPos selected;
        if (PeripheralLinkerItem.isLinker(main)) selected = main.get(ModRegistry.SELECTED_HOST.get());
        else if (PeripheralLinkerItem.isLinker(off)) selected = off.get(ModRegistry.SELECTED_HOST.get());
        else return;
        if (selected != null && !selected.dimension().equals(mc.level.dimension())) selected = null;

        var view = ClientLinkView.get();
        if (view.hosts().isEmpty() && selected == null) return;

        var renderType = Config.RENDER_THROUGH_WALLS.get() ? LinkRenderTypes.LINES_THROUGH_WALLS : RenderType.lines();
        var buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(renderType);

        var camera = event.getCamera().getPosition();
        var poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);

        double maxDist = Config.LINE_RENDER_DISTANCE.get();
        double maxDistSq = maxDist * maxDist;
        var eye = player.getEyePosition();

        boolean drewSelected = false;
        for (var host : view.hosts()) {
            var hostCentre = Vec3.atCenterOf(host.host());
            var isSelected = selected != null && selected.pos().equals(host.host());
            if (!isSelected && hostCentre.distanceToSqr(eye) > maxDistSq) continue;
            drewSelected |= isSelected;

            box(poseStack, lines, new AABB(host.host()).inflate(isSelected ? 0.04 : 0.02), isSelected ? 0x40E0FF : 0xC0C0C0, isSelected ? 1f : 0.6f);
            for (var link : host.links()) {
                var colour = statusColour(link.status());
                var alpha = isSelected ? 1f : 0.6f;
                line(poseStack, lines, hostCentre, Vec3.atCenterOf(link.pos()), colour, alpha);
                box(poseStack, lines, new AABB(link.pos()).inflate(0.01), colour, alpha);
            }
        }
        if (selected != null && !drewSelected) {
            box(poseStack, lines, new AABB(selected.pos()).inflate(0.04), 0x40E0FF, 1f);
        }

        poseStack.popPose();
        buffers.endBatch(renderType);
    }

    private static void box(PoseStack poseStack, VertexConsumer consumer, AABB box, int rgb, float alpha) {
        LevelRenderer.renderLineBox(poseStack, consumer, box, r(rgb), g(rgb), b(rgb), alpha);
    }

    private static void line(PoseStack poseStack, VertexConsumer consumer, Vec3 from, Vec3 to, int rgb, float alpha) {
        var pose = poseStack.last();
        var dir = to.subtract(from).normalize();
        float nx = (float) dir.x, ny = (float) dir.y, nz = (float) dir.z;
        consumer.addVertex(pose, (float) from.x, (float) from.y, (float) from.z).setColor(r(rgb), g(rgb), b(rgb), alpha).setNormal(pose, nx, ny, nz);
        consumer.addVertex(pose, (float) to.x, (float) to.y, (float) to.z).setColor(r(rgb), g(rgb), b(rgb), alpha).setNormal(pose, nx, ny, nz);
    }

    private static float r(int rgb) {
        return ((rgb >> 16) & 0xFF) / 255f;
    }

    private static float g(int rgb) {
        return ((rgb >> 8) & 0xFF) / 255f;
    }

    private static float b(int rgb) {
        return (rgb & 0xFF) / 255f;
    }

    static {
        // Keep the colour table in sync with the server-side enum.
        if (STATUS_COLOURS.length != HubScheduler.LinkStatus.values().length) {
            throw new IllegalStateException("Status colour table out of date");
        }
    }
}
