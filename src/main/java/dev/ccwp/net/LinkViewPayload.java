package dev.ccwp.net;

import dev.ccwp.CCWirelessPeripherals;
import dev.ccwp.client.ClientLinkView;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;
import java.util.Optional;

/** Server → client: the links near a player holding a Peripheral Linker, for drawing lines. */
public record LinkViewPayload(List<HostView> hosts) implements CustomPacketPayload {
    public static final LinkViewPayload EMPTY = new LinkViewPayload(List.of());

    public static final Type<LinkViewPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(CCWirelessPeripherals.MOD_ID, "link_view"));

    /** Status codes, see {@link dev.ccwp.hub.HubScheduler.LinkStatus}. */
    public record LinkView(BlockPos pos, String name, byte status) {
        public static final StreamCodec<ByteBuf, LinkView> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, LinkView::pos,
            ByteBufCodecs.STRING_UTF8, LinkView::name,
            ByteBufCodecs.BYTE, LinkView::status,
            LinkView::new);
    }

    /**
     * @param computerId The computer id of a pocket computer or turtle host, or -1 for a placed host.
     * @param host       Where the host is: a placed host's block, or a mobile host's current position if it is
     *                   running in the player's dimension. Empty for a mobile host that isn't (links are still sent,
     *                   so their blocks can be drawn).
     * @param links      Links in the player's dimension.
     */
    public record HostView(int computerId, Optional<BlockPos> host, List<LinkView> links) {
        public static final StreamCodec<ByteBuf, HostView> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, HostView::computerId,
            ByteBufCodecs.optional(BlockPos.STREAM_CODEC), HostView::host,
            LinkView.STREAM_CODEC.apply(ByteBufCodecs.list()), HostView::links,
            HostView::new);

        public boolean isMobile() {
            return computerId >= 0;
        }
    }

    public static final StreamCodec<ByteBuf, LinkViewPayload> STREAM_CODEC = StreamCodec.composite(
        HostView.STREAM_CODEC.apply(ByteBufCodecs.list()), LinkViewPayload::hosts,
        LinkViewPayload::new);

    @Override
    public Type<LinkViewPayload> type() {
        return TYPE;
    }

    public static void handle(LinkViewPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientLinkView.set(payload));
    }
}
