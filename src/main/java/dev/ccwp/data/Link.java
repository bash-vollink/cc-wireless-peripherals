package dev.ccwp.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * A wireless link from a host to a peripheral block.
 *
 * @param pos    The peripheral block (same dimension as the host).
 * @param face   The face that was clicked; used as the capability side when looking up the peripheral.
 * @param type   The peripheral type last seen. When it changes, an automatic name is replaced by a fresh one, like wired
 *               modems do. A custom name is kept.
 * @param name   The name exposed to Lua, e.g. {@code minecraft:chest_w0} or a custom name set by a player.
 * @param custom Whether {@code name} was chosen by a player (see {@link LinkNames}).
 */
public record Link(BlockPos pos, Direction face, String type, String name, boolean custom) {
    public static final Codec<Link> CODEC = RecordCodecBuilder.create(i -> i.group(
        BlockPos.CODEC.fieldOf("pos").forGetter(Link::pos),
        Direction.CODEC.fieldOf("face").forGetter(Link::face),
        Codec.STRING.fieldOf("type").forGetter(Link::type),
        Codec.STRING.fieldOf("name").forGetter(Link::name),
        Codec.BOOL.optionalFieldOf("custom", false).forGetter(Link::custom)
    ).apply(i, Link::new));

    public Link {
        pos = pos.immutable();
    }

    public Link withName(String type, String name, boolean custom) {
        return new Link(pos, face, type, name, custom);
    }
}
