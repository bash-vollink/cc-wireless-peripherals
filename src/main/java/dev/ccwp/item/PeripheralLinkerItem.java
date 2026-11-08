package dev.ccwp.item;

import dev.ccwp.Blacklists;
import dev.ccwp.Config;
import dev.ccwp.ModRegistry;
import dev.ccwp.data.HostKey;
import dev.ccwp.data.LinkSavedData;
import dev.ccwp.hub.HubScheduler;
import dev.ccwp.hub.PeripheralLookup;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
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

import javax.annotation.Nullable;
import java.util.List;

/**
 * Right-click a host (wireless/ender modem, CC: Modern server) to select it, then right-click blocks to link or unlink
 * them. Sneak + right-click a linked block to rename its link to the linker's anvil name (or back to an automatic name
 * if the linker has none). Sneak + right-click the air to clear the selection. While held, links are drawn in the
 * world and the name of the linked block under the crosshair is shown.
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
        } else if (!player.isSecondaryUseActive() || !renameLink(stack, player, level, pos)) {
            toggleLink(stack, player, level, pos, context);
        }
        return InteractionResult.SUCCESS;
    }

    /** The host selected on this linker, if any. */
    public static @Nullable HostKey selectedHost(ItemStack stack) {
        var selected = stack.get(ModRegistry.SELECTED_HOST.get());
        return selected == null ? null : HostKey.of(selected);
    }

    /**
     * Pick the link to {@code pos} to act on: the one from {@code preferred} if it has one, otherwise the only link to
     * that block.
     *
     * @return The link, or {@code null} with a message sent if there is none or the choice is ambiguous.
     */
    @Nullable
    public static LinkSavedData.HostLink pickLink(LinkSavedData data, ResourceKey<Level> dimension,
                                                             BlockPos pos, @Nullable HostKey preferred,
                                                             java.util.function.Consumer<Component> fail) {
        var links = data.linksTo(dimension, pos);
        if (preferred != null) {
            for (var l : links) if (l.host().equals(preferred)) return l;
        }
        if (links.size() == 1) return links.get(0);
        fail.accept(Component.translatable(links.isEmpty() ? "message.ccwirelessperipherals.not_linked" : "message.ccwirelessperipherals.ambiguous_link"));
        return null;
    }

    /**
     * Rename the link to a block to this linker's anvil name, or reset it to an automatic name.
     *
     * @return {@code false} if the block isn't linked at all, so the click should link it instead.
     */
    private static boolean renameLink(ItemStack stack, Player player, ServerLevel level, BlockPos pos) {
        var data = LinkSavedData.get(level.getServer());
        if (data.linksTo(level.dimension(), pos).isEmpty()) return false;

        var target = pickLink(data, level.dimension(), pos, selectedHost(stack), m -> tell(player, m.copy().withStyle(ChatFormatting.RED)));
        if (target == null) return true;

        var customName = stack.get(DataComponents.CUSTOM_NAME);
        var name = customName == null ? null : customName.getString().trim();
        rename(data, target, name, (m, ok) -> tell(player, m));
        return true;
    }

    /** Rename a link (null: back to an automatic name) and report the outcome. Shared with {@code /ccwp rename}. */
    public static boolean rename(LinkSavedData data, LinkSavedData.HostLink target, @Nullable String name,
                                 java.util.function.BiConsumer<Component, Boolean> report) {
        var old = target.link().name();
        var result = data.rename(target.host(), target.link(), name);
        if (result.error() != null) {
            report.accept(Component.translatable(result.error(), name == null ? old : name).withStyle(ChatFormatting.RED), false);
            return false;
        }
        var updated = result.link();
        if (updated.name().equals(old)) {
            report.accept(Component.translatable("message.ccwirelessperipherals.name_unchanged", old).withStyle(ChatFormatting.GRAY), true);
            return true;
        }
        HubScheduler.markHostDirty(target.host());
        report.accept(Component.translatable("message.ccwirelessperipherals.renamed", old, updated.name()).withStyle(ChatFormatting.GREEN), true);
        return true;
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

        var link = data.createLink(level.dimension(), pos, face, type);
        data.addLink(host, link);
        HubScheduler.markHostDirty(host);
        tell(player, Component.translatable("message.ccwirelessperipherals.linked", link.name()).withStyle(ChatFormatting.GREEN));
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
        var customName = stack.get(DataComponents.CUSTOM_NAME);
        if (customName != null) {
            tooltip.add(Component.translatable("tooltip.ccwirelessperipherals.rename", customName.getString().trim()).withStyle(ChatFormatting.GRAY));
        }
        tooltip.add(Component.translatable("tooltip.ccwirelessperipherals.help").withStyle(ChatFormatting.DARK_GRAY));
    }

    private static void tell(Player player, Component message) {
        player.displayClientMessage(message, true);
    }
}
