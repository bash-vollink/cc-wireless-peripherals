package dev.ccwp.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.ccwp.Blacklists;
import dev.ccwp.ModRegistry;
import dev.ccwp.data.HostKey;
import dev.ccwp.data.LinkSavedData;
import dev.ccwp.hub.HubScheduler;
import dev.ccwp.item.PeripheralLinkerItem;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;

/**
 * {@code /ccwp list [pos]}, {@code /ccwp rename <peripheral pos> [name]}, {@code /ccwp clear <pos>},
 * {@code /ccwp reload}.
 */
public final class CcwpCommand {
    private CcwpCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ccwp")
            .then(Commands.literal("list")
                .executes(CcwpCommand::listSelected)
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                    .executes(ctx -> list(ctx, new HostKey(ctx.getSource().getLevel().dimension(), BlockPosArgument.getBlockPos(ctx, "pos"))))))
            .then(Commands.literal("rename")
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                    .executes(ctx -> rename(ctx, null))
                    .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(ctx -> rename(ctx, StringArgumentType.getString(ctx, "name").trim())))))
            .then(Commands.literal("clear")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                    .executes(CcwpCommand::clear)))
            .then(Commands.literal("reload")
                .requires(s -> s.hasPermission(2))
                .executes(ctx -> {
                    Blacklists.reload();
                    HubScheduler.markAllDirty();
                    ctx.getSource().sendSuccess(() -> Component.translatable("command.ccwirelessperipherals.reloaded"), true);
                    return 1;
                })));
    }

    private static int listSelected(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var player = ctx.getSource().getPlayerOrException();
        for (var stack : new net.minecraft.world.item.ItemStack[]{ player.getMainHandItem(), player.getOffhandItem() }) {
            if (!PeripheralLinkerItem.isLinker(stack)) continue;
            var selected = stack.get(ModRegistry.SELECTED_HOST.get());
            if (selected != null) return list(ctx, HostKey.of(selected));
        }
        ctx.getSource().sendFailure(Component.translatable("command.ccwirelessperipherals.no_selection"));
        return 0;
    }

    private static int list(CommandContext<CommandSourceStack> ctx, HostKey host) {
        var source = ctx.getSource();
        var links = LinkSavedData.get(source.getServer()).getLinks(host);
        var p = host.pos();
        source.sendSuccess(() -> Component.translatable("command.ccwirelessperipherals.list_header",
            p.getX(), p.getY(), p.getZ(), links.size()).withStyle(ChatFormatting.AQUA), false);
        for (var link : links) {
            var status = HubScheduler.status(host, link.pos());
            var colour = switch (status) {
                case CONNECTED -> ChatFormatting.GREEN;
                case MISSING -> ChatFormatting.RED;
                case IDLE -> ChatFormatting.YELLOW;
            };
            var lp = link.pos();
            source.sendSuccess(() -> Component.literal(" - " + link.name() + " @ " + lp.getX() + " " + lp.getY() + " " + lp.getZ()
                + " [" + status.name().toLowerCase(java.util.Locale.ROOT) + "]").withStyle(colour), false);
        }
        return links.size();
    }

    private static int rename(CommandContext<CommandSourceStack> ctx, @Nullable String name) throws CommandSyntaxException {
        var source = ctx.getSource();
        var level = source.getLevel();
        var pos = BlockPosArgument.getBlockPos(ctx, "pos");

        HostKey preferred = null;
        var player = source.getPlayer();
        if (player != null) {
            // Same rule as editing links with the linker: players need to be able to modify the block.
            if (!source.hasPermission(2) && !level.mayInteract(player, pos)) {
                source.sendFailure(Component.translatable("message.ccwirelessperipherals.no_permission"));
                return 0;
            }
            for (var stack : new net.minecraft.world.item.ItemStack[]{ player.getMainHandItem(), player.getOffhandItem() }) {
                if (PeripheralLinkerItem.isLinker(stack) && (preferred = PeripheralLinkerItem.selectedHost(stack)) != null) break;
            }
        }

        var data = LinkSavedData.get(source.getServer());
        var target = PeripheralLinkerItem.pickLink(data, level.dimension(), pos, preferred, source::sendFailure);
        if (target == null) return 0;
        var ok = PeripheralLinkerItem.rename(data, target, name, (message, success) -> {
            if (success) source.sendSuccess(() -> message, true);
            else source.sendFailure(message);
        });
        return ok ? 1 : 0;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var source = ctx.getSource();
        var host = new HostKey(source.getLevel().dimension(), BlockPosArgument.getBlockPos(ctx, "pos"));
        var removed = LinkSavedData.get(source.getServer()).removeHost(host);
        if (removed) HubScheduler.markHostDirty(host);
        source.sendSuccess(() -> Component.translatable(removed ? "command.ccwirelessperipherals.cleared" : "command.ccwirelessperipherals.nothing_to_clear"), true);
        return removed ? 1 : 0;
    }
}
