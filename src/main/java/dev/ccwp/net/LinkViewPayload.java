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

    public record HostView(BlockPos host, List<LinkView> links) {
        public static final StreamCodec<ByteBuf, HostView> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, HostView::host,
            LinkView.STREAM_CODEC.apply(ByteBufCodecs.list()), HostView::links,
            HostView::new);
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
