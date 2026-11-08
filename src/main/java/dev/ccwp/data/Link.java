package dev.ccwp.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * A wireless link from a host to a peripheral block.
 *
 * @param pos  The peripheral block (same dimension as the host).
 * @param face The face that was clicked; used as the capability side when looking up the peripheral.
 * @param type The peripheral type at link time; a change of type gets a fresh name, like wired modems.
 * @param name The wired-style name exposed to Lua, e.g. {@code minecraft:chest_0}.
 */
public record Link(BlockPos pos, Direction face, String type, String name) {
    public static final Codec<Link> CODEC = RecordCodecBuilder.create(i -> i.group(
        BlockPos.CODEC.fieldOf("pos").forGetter(Link::pos),
        Direction.CODEC.fieldOf("face").forGetter(Link::face),
        Codec.STRING.fieldOf("type").forGetter(Link::type),
        Codec.STRING.fieldOf("name").forGetter(Link::name)
    ).apply(i, Link::new));

    public Link {
        pos = pos.immutable();
    }

    public Link withName(String type, String name) {
        return new Link(pos, face, type, name);
    }
}
