package dev.ccwp.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.ccwp.Blacklists;
import dev.ccwp.data.HostKey;
import dev.ccwp.data.LinkSavedData;
import dev.ccwp.hub.HubScheduler;
import dev.ccwp.item.PeripheralLinkerItem;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * {@code /ccwp list [pos | computer <id>]}, {@code /ccwp rename <peripheral pos> [name]},
 * {@code /ccwp clear <pos> | computer <id>}, {@code /ccwp reload}.
 */
public final class CcwpCommand {
    private CcwpCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ccwp")
            .then(Commands.literal("list")
                .executes(CcwpCommand::listSelected)
                .then(Commands.literal("computer")
                    .then(Commands.argument("id", IntegerArgumentType.integer(0))
                        .executes(ctx -> list(ctx, new HostKey.Mobile(IntegerArgumentType.getInteger(ctx, "id"))))))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                    .executes(ctx -> list(ctx, new HostKey.Placed(ctx.getSource().getLevel().dimension(), BlockPosArgument.getBlockPos(ctx, "pos"))))))
            .then(Commands.literal("rename")
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                    .executes(ctx -> rename(ctx, null))
                    .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(ctx -> rename(ctx, StringArgumentType.getString(ctx, "name").trim())))))
            .then(Commands.literal("clear")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("computer")
                    .then(Commands.argument("id", IntegerArgumentType.integer(0))
                        .executes(ctx -> clear(ctx, new HostKey.Mobile(IntegerArgumentType.getInteger(ctx, "id"))))))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                    .executes(ctx -> clear(ctx, new HostKey.Placed(ctx.getSource().getLevel().dimension(), BlockPosArgument.getBlockPos(ctx, "pos"))))))
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
        for (var stack : new ItemStack[]{ player.getMainHandItem(), player.getOffhandItem() }) {
            if (!PeripheralLinkerItem.isLinker(stack)) continue;
            var selected = PeripheralLinkerItem.selectedHost(stack);
            if (selected != null) return list(ctx, selected);
        }
        ctx.getSource().sendFailure(Component.translatable("command.ccwirelessperipherals.no_selection"));
        return 0;
    }

    private static int list(CommandContext<CommandSourceStack> ctx, HostKey host) {
        var source = ctx.getSource();
        var data = LinkSavedData.get(source.getServer());
        var links = data.getLinks(host);
        if (host instanceof HostKey.Placed placed) {
            var p = placed.pos();
            source.sendSuccess(() -> Component.translatable("command.ccwirelessperipherals.list_header",
                p.getX(), p.getY(), p.getZ(), links.size()).withStyle(ChatFormatting.AQUA), false);
        } else {
            var id = ((HostKey.Mobile) host).computerId();
            source.sendSuccess(() -> Component.translatable("command.ccwirelessperipherals.list_header_computer",
                id, links.size()).withStyle(ChatFormatting.AQUA), false);
        }
        for (var link : links) {
            var status = HubScheduler.status(host, link.target());
            var colour = switch (status) {
                case CONNECTED -> ChatFormatting.GREEN;
                case MISSING -> ChatFormatting.RED;
                case IDLE -> ChatFormatting.YELLOW;
            };
            var lp = link.pos();
            var where = lp.getX() + " " + lp.getY() + " " + lp.getZ()
                + (host instanceof HostKey.Mobile ? " (" + link.dimension().location() + ")" : "");
            source.sendSuccess(() -> Component.literal(" - " + data.nameOf(link.target()) + " @ " + where
                + " [" + status.name().toLowerCase(Locale.ROOT) + "]").withStyle(colour), false);
        }
        return links.size();
    }

    private static int rename(CommandContext<CommandSourceStack> ctx, @Nullable String name) throws CommandSyntaxException {
        var source = ctx.getSource();
        var level = source.getLevel();
        var pos = BlockPosArgument.getBlockPos(ctx, "pos");

        // Same rule as editing links with the linker: players need to be able to modify the block.
        var player = source.getPlayer();
        if (player != null && !source.hasPermission(2) && !level.mayInteract(player, pos)) {
            source.sendFailure(Component.translatable("message.ccwirelessperipherals.no_permission"));
            return 0;
        }

        var data = LinkSavedData.get(source.getServer());
        var ok = PeripheralLinkerItem.rename(data, GlobalPos.of(level.dimension(), pos), name, (message, success) -> {
            if (success) source.sendSuccess(() -> message, true);
            else source.sendFailure(message);
        });
        return ok ? 1 : 0;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx, HostKey host) {
        var source = ctx.getSource();
        var removed = LinkSavedData.get(source.getServer()).removeHost(host);
        if (removed) HubScheduler.markHostDirty(host);
        source.sendSuccess(() -> Component.translatable(removed ? "command.ccwirelessperipherals.cleared" : "command.ccwirelessperipherals.nothing_to_clear"), true);
        return removed ? 1 : 0;
    }
}
