package dev.ccwp.item;

import dan200.computercraft.shared.turtle.blocks.TurtleBlockEntity;
import dev.ccwp.Blacklists;
import dev.ccwp.Config;
import dev.ccwp.ModRegistry;
import dev.ccwp.data.HostKey;
import dev.ccwp.data.LinkSavedData;
import dev.ccwp.hub.HubScheduler;
import dev.ccwp.hub.MobileHosts;
import dev.ccwp.hub.PeripheralLookup;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
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
import java.util.function.BiConsumer;

/**
 * Selects a host, then links and unlinks peripherals to it.
 * <ul>
 *     <li>Right-click a wireless/ender modem or CC: Modern server to select it.</li>
 *     <li>Sneak + right-click a turtle to select it (it needs an ender modem). A plain right-click links the turtle as
 *     a peripheral instead.</li>
 *     <li>Hold a pocket computer (with an ender modem) in the off hand and right-click the air to select it.</li>
 *     <li>Right-click blocks to link or unlink them to the selected host.</li>
 *     <li>Sneak + right-click a linked block to rename it to the linker's anvil name, or back to an automatic name if
 *     the linker has none. Names belong to the block, so this renames it for every host.</li>
 *     <li>Sneak + right-click the air to clear the selection.</li>
 * </ul>
 * While held, links are drawn in the world and the name of the linked block under the crosshair is shown.
 */
public class PeripheralLinkerItem extends Item {
    public PeripheralLinkerItem(Properties properties) {
        super(properties);
    }

    public static boolean isLinker(ItemStack stack) {
        return stack.getItem() instanceof PeripheralLinkerItem;
    }

    /** The host selected on this linker, if any. */
    public static @Nullable HostKey selectedHost(ItemStack stack) {
        var computer = stack.get(ModRegistry.SELECTED_COMPUTER.get());
        if (computer != null) return new HostKey.Mobile(computer);
        var selected = stack.get(ModRegistry.SELECTED_HOST.get());
        return selected == null ? null : HostKey.of(selected);
    }

    private static boolean hasSelection(ItemStack stack) {
        return stack.has(ModRegistry.SELECTED_HOST.get()) || stack.has(ModRegistry.SELECTED_COMPUTER.get());
    }

    private static void clearSelection(ItemStack stack) {
        stack.remove(ModRegistry.SELECTED_HOST.get());
        stack.remove(ModRegistry.SELECTED_COMPUTER.get());
    }

    /** Runs before the block is used, so computer/server/turtle GUIs don't open while linking. */
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
        var sneaking = player.isSecondaryUseActive();
        if (Blacklists.isHostType(state)) {
            selectPlacedHost(stack, player, level, pos);
        } else if (sneaking && level.getBlockEntity(pos) instanceof TurtleBlockEntity turtle) {
            selectMobileHost(stack, player, MobileHosts.checkTurtle(turtle), "message.ccwirelessperipherals.selected_turtle");
        } else if (!sneaking || !renameLink(stack, player, level, pos)) {
            toggleLink(stack, player, level, pos, context);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (player.isSecondaryUseActive()) {
            if (!hasSelection(stack)) return InteractionResultHolder.pass(stack);
            if (!level.isClientSide) {
                clearSelection(stack);
                tell(player, Component.translatable("message.ccwirelessperipherals.cleared").withStyle(ChatFormatting.GRAY));
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        // Linker in the main hand, pocket computer in the off hand: select the pocket computer. Consuming the use
        // here also keeps the pocket computer's own GUI from opening.
        if (hand == InteractionHand.MAIN_HAND && MobileHosts.isPocketComputer(player.getOffhandItem())) {
            if (!level.isClientSide) {
                selectMobileHost(stack, player, MobileHosts.checkPocket(player.getOffhandItem()), "message.ccwirelessperipherals.selected_pocket");
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        return InteractionResultHolder.pass(stack);
    }

    private static void selectPlacedHost(ItemStack stack, Player player, ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        if (!Blacklists.isHost(state)) {
            tell(player, Component.translatable("message.ccwirelessperipherals.host_blacklisted").withStyle(ChatFormatting.RED));
            return;
        }
        clearSelection(stack);
        stack.set(ModRegistry.SELECTED_HOST.get(), GlobalPos.of(level.dimension(), pos));
        var count = LinkSavedData.get(level.getServer()).getLinks(new HostKey.Placed(level.dimension(), pos)).size();
        tell(player, Component.translatable("message.ccwirelessperipherals.selected",
            pos.getX(), pos.getY(), pos.getZ(), count, rangeText(Blacklists.rangeFor(state))).withStyle(ChatFormatting.AQUA));
    }

    private static void selectMobileHost(ItemStack stack, Player player, MobileHosts.Check check, String message) {
        if (check.key() == null) {
            tell(player, Component.translatable(check.error()).withStyle(ChatFormatting.RED));
            return;
        }
        var id = check.key().computerId();
        clearSelection(stack);
        stack.set(ModRegistry.SELECTED_COMPUTER.get(), id);
        var server = player.getServer();
        var count = server == null ? 0 : LinkSavedData.get(server).getLinks(check.key()).size();
        tell(player, Component.translatable(message, id, count).withStyle(ChatFormatting.AQUA));
    }

    public static Component rangeText(int range) {
        return range > 0 ? Component.literal(Integer.toString(range)) : Component.translatable("message.ccwirelessperipherals.unlimited");
    }

    /**
     * Rename a linked block to this linker's anvil name, or reset it to an automatic name.
     *
     * @return {@code false} if the block isn't linked at all, so the click should link it instead.
     */
    private static boolean renameLink(ItemStack stack, Player player, ServerLevel level, BlockPos pos) {
        var data = LinkSavedData.get(level.getServer());
        var target = GlobalPos.of(level.dimension(), pos);
        if (!data.isLinked(target)) return false;

        var customName = stack.get(DataComponents.CUSTOM_NAME);
        var name = customName == null ? null : customName.getString().trim();
        rename(data, target, name, (m, ok) -> tell(player, m));
        return true;
    }

    /**
     * Rename a linked block for every host linking it ({@code null}: back to an automatic name) and report the
     * outcome. Shared with {@code /ccwp rename}.
     */
    public static boolean rename(LinkSavedData data, GlobalPos target, @Nullable String name, BiConsumer<Component, Boolean> report) {
        var old = data.nameOf(target);
        var result = data.rename(target, name);
        if (result.error() != null) {
            report.accept(Component.translatable(result.error(), name == null ? String.valueOf(old) : name).withStyle(ChatFormatting.RED), false);
            return false;
        }
        if (result.name().equals(old)) {
            report.accept(Component.translatable("message.ccwirelessperipherals.name_unchanged", old).withStyle(ChatFormatting.GRAY), true);
            return true;
        }
        HubScheduler.markHostsDirty(data.hostsLinking(target));
        report.accept(Component.translatable("message.ccwirelessperipherals.renamed", old, result.name()).withStyle(ChatFormatting.GREEN), true);
        return true;
    }

    private static void toggleLink(ItemStack stack, Player player, ServerLevel level, BlockPos pos, UseOnContext context) {
        var host = selectedHost(stack);
        if (host == null) {
            tell(player, Component.translatable("message.ccwirelessperipherals.no_host").withStyle(ChatFormatting.YELLOW));
            return;
        }

        var server = level.getServer();
        var data = LinkSavedData.get(server);
        var target = GlobalPos.of(level.dimension(), pos);

        // Clicking an already-linked block unlinks it (works even if the host is unloaded or elsewhere).
        if (data.findLink(host, target) != null) {
            var name = data.nameOf(target);
            data.removeLink(host, target);
            HubScheduler.markHostDirty(host);
            tell(player, Component.translatable("message.ccwirelessperipherals.unlinked", String.valueOf(name)).withStyle(ChatFormatting.GOLD));
            return;
        }

        if (host instanceof HostKey.Placed placed) {
            if (!checkPlacedHost(stack, player, level, data, placed, pos)) return;
        } else if (host instanceof HostKey.Mobile mobile
            && level.getBlockEntity(pos) instanceof TurtleBlockEntity turtle && turtle.getComputerID() == mobile.computerId()) {
            tell(player, Component.translatable("message.ccwirelessperipherals.link_self").withStyle(ChatFormatting.RED));
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

        var nameBefore = data.nameOf(target);
        data.addLink(host, target, face, type);
        var name = data.nameOf(target);
        HubScheduler.markHostDirty(host);
        // Linking can rename a block (a remembered name for another type): other hosts must follow.
        if (nameBefore != null && !nameBefore.equals(name)) HubScheduler.markHostsDirty(data.hostsLinking(target));
        tell(player, Component.translatable("message.ccwirelessperipherals.linked", name).withStyle(ChatFormatting.GREEN));
    }

    /** Checks for linking to a placed host: same dimension, loaded, still a host, and in range. */
    private static boolean checkPlacedHost(ItemStack stack, Player player, ServerLevel level, LinkSavedData data, HostKey.Placed host, BlockPos pos) {
        if (!host.dimension().equals(level.dimension())) {
            tell(player, Component.translatable("message.ccwirelessperipherals.wrong_dimension").withStyle(ChatFormatting.RED));
            return false;
        }
        if (!level.isLoaded(host.pos())) {
            tell(player, Component.translatable("message.ccwirelessperipherals.host_unloaded").withStyle(ChatFormatting.RED));
            return false;
        }
        var hostState = level.getBlockState(host.pos());
        if (!Blacklists.isHostType(hostState)) {
            clearSelection(stack);
            if (data.removeHost(host)) HubScheduler.markHostDirty(host);
            tell(player, Component.translatable("message.ccwirelessperipherals.host_gone").withStyle(ChatFormatting.RED));
            return false;
        }
        if (!Blacklists.isHost(hostState)) {
            tell(player, Component.translatable("message.ccwirelessperipherals.host_blacklisted").withStyle(ChatFormatting.RED));
            return false;
        }
        var range = Blacklists.rangeFor(hostState);
        if (range > 0 && Vec3.atCenterOf(host.pos()).distanceToSqr(Vec3.atCenterOf(pos)) > (double) range * range) {
            tell(player, Component.translatable("message.ccwirelessperipherals.out_of_range", range).withStyle(ChatFormatting.RED));
            return false;
        }
        return true;
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return hasSelection(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        var computer = stack.get(ModRegistry.SELECTED_COMPUTER.get());
        var selected = stack.get(ModRegistry.SELECTED_HOST.get());
        if (computer != null) {
            tooltip.add(Component.translatable("tooltip.ccwirelessperipherals.selected_computer", computer).withStyle(ChatFormatting.AQUA));
        } else if (selected != null) {
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
