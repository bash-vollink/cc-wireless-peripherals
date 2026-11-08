package dev.ccwp.item;

import dev.ccwp.Blacklists;
import dev.ccwp.Config;
import dev.ccwp.ModRegistry;
import dev.ccwp.data.HostKey;
import dev.ccwp.data.Link;
import dev.ccwp.data.LinkSavedData;
import dev.ccwp.hub.HubScheduler;
import dev.ccwp.hub.PeripheralLookup;
import dev.ccwp.hub.WirelessHub;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Right-click a host (wireless/ender modem, CC: Modern server) to select it, then right-click blocks to link or unlink
 * them. Sneak + right-click the air to clear the selection. While held, links are drawn in the world.
 */
public class PeripheralLinkerItem extends Item {
    public PeripheralLinkerItem(Properties properties) {
        super(properties);
    }

    public static boolean isLinker(ItemStack stack) {
        return stack.getItem() instanceof PeripheralLinkerItem;
    }

    /** Runs before the block is used, so computer/server GUIs don't open while linking. */
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        var player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        if (!(context.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;

        var pos = context.getClickedPos();
        if (!level.mayInteract(player, pos)) {
            tell(player, Component.translatable("message.ccwirelessperipherals.no_permission").withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }

        var state = level.getBlockState(pos);
        if (Blacklists.isHostType(state)) {
            selectHost(stack, player, level, pos);
        } else {
            toggleLink(stack, player, level, pos, context);
        }
        return InteractionResult.SUCCESS;
    }

    private static void selectHost(ItemStack stack, Player player, ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        if (!Blacklists.isHost(state)) {
            tell(player, Component.translatable("message.ccwirelessperipherals.host_blacklisted").withStyle(ChatFormatting.RED));
            return;
        }
        stack.set(ModRegistry.SELECTED_HOST.get(), GlobalPos.of(level.dimension(), pos));
        var count = LinkSavedData.get(level.getServer()).getLinks(new HostKey(level.dimension(), pos)).size();
        tell(player, Component.translatable("message.ccwirelessperipherals.selected",
            pos.getX(), pos.getY(), pos.getZ(), count, Blacklists.rangeFor(state)).withStyle(ChatFormatting.AQUA));
    }

    private static void toggleLink(ItemStack stack, Player player, ServerLevel level, BlockPos pos, UseOnContext context) {
        var selected = stack.get(ModRegistry.SELECTED_HOST.get());
        if (selected == null) {
            tell(player, Component.translatable("message.ccwirelessperipherals.no_host").withStyle(ChatFormatting.YELLOW));
            return;
        }
        if (!selected.dimension().equals(level.dimension())) {
            tell(player, Component.translatable("message.ccwirelessperipherals.wrong_dimension").withStyle(ChatFormatting.RED));
            return;
        }

        var server = level.getServer();
        var host = HostKey.of(selected);
        var data = LinkSavedData.get(server);

        // Clicking an already-linked block unlinks it (works even if the host is unloaded).
        var existing = data.findLink(host, pos);
        if (existing != null) {
            data.removeLink(host, pos);
            HubScheduler.markHostDirty(host);
            tell(player, Component.translatable("message.ccwirelessperipherals.unlinked", existing.name()).withStyle(ChatFormatting.GOLD));
            return;
        }

        if (!level.isLoaded(host.pos())) {
            tell(player, Component.translatable("message.ccwirelessperipherals.host_unloaded").withStyle(ChatFormatting.RED));
            return;
        }
        var hostState = level.getBlockState(host.pos());
        if (!Blacklists.isHostType(hostState)) {
            stack.remove(ModRegistry.SELECTED_HOST.get());
            if (data.removeHost(host)) HubScheduler.markHostDirty(host);
            tell(player, Component.translatable("message.ccwirelessperipherals.host_gone").withStyle(ChatFormatting.RED));
            return;
        }
        if (!Blacklists.isHost(hostState)) {
            tell(player, Component.translatable("message.ccwirelessperipherals.host_blacklisted").withStyle(ChatFormatting.RED));
            return;
        }

        var range = Blacklists.rangeFor(hostState);
        if (Vec3.atCenterOf(host.pos()).distanceToSqr(Vec3.atCenterOf(pos)) > (double) range * range) {
            tell(player, Component.translatable("message.ccwirelessperipherals.out_of_range", range).withStyle(ChatFormatting.RED));
            return;
        }

        var max = Config.MAX_LINKS_PER_HOST.get();
        if (max > 0 && data.getLinks(host).size() >= max) {
            tell(player, Component.translatable("message.ccwirelessperipherals.too_many", max).withStyle(ChatFormatting.RED));
            return;
        }

        var face = context.getClickedFace();
        var peripheral = PeripheralLookup.find(level, pos, face);
        if (peripheral == null || peripheral.getType() == null) {
            tell(player, Component.translatable("message.ccwirelessperipherals.no_peripheral").withStyle(ChatFormatting.RED));
            return;
        }
        var type = peripheral.getType();
        if (Blacklists.isPeripheralBlacklisted(level.getBlockState(pos), type)) {
            tell(player, Component.translatable("message.ccwirelessperipherals.peripheral_blacklisted", type).withStyle(ChatFormatting.RED));
            return;
        }

        var name = WirelessHub.nextName(server, type);
        data.addLink(host, new Link(pos, face, type, name));
        HubScheduler.markHostDirty(host);
        tell(player, Component.translatable("message.ccwirelessperipherals.linked", name).withStyle(ChatFormatting.GREEN));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (player.isSecondaryUseActive() && stack.has(ModRegistry.SELECTED_HOST.get())) {
            if (!level.isClientSide) {
                stack.remove(ModRegistry.SELECTED_HOST.get());
                tell(player, Component.translatable("message.ccwirelessperipherals.cleared").withStyle(ChatFormatting.GRAY));
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        return InteractionResultHolder.pass(stack);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return stack.has(ModRegistry.SELECTED_HOST.get());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        var selected = stack.get(ModRegistry.SELECTED_HOST.get());
        if (selected != null) {
            var p = selected.pos();
            tooltip.add(Component.translatable("tooltip.ccwirelessperipherals.selected", p.getX(), p.getY(), p.getZ(),
                selected.dimension().location().toString()).withStyle(ChatFormatting.AQUA));
        } else {
            tooltip.add(Component.translatable("tooltip.ccwirelessperipherals.none").withStyle(ChatFormatting.GRAY));
        }
        tooltip.add(Component.translatable("tooltip.ccwirelessperipherals.help").withStyle(ChatFormatting.DARK_GRAY));
    }

    private static void tell(Player player, Component message) {
        player.displayClientMessage(message, true);
    }
}
