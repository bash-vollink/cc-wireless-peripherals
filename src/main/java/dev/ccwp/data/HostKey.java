package dev.ccwp.data;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Identifies a host: the owner of a set of wireless links.
 * <ul>
 *     <li>{@link Placed}: a block that hosts in place (wireless/ender modem, CC: Modern server), keyed by dimension and
 *     position.</li>
 *     <li>{@link Mobile}: a pocket computer or turtle with an ender modem, keyed by its computer id so its links
 *     survive it moving. It can only be reached while it is running somewhere loaded.</li>
 * </ul>
 */
public sealed interface HostKey permits HostKey.Placed, HostKey.Mobile {
    record Placed(ResourceKey<Level> dimension, BlockPos pos) implements HostKey {
        static final Codec<Placed> CODEC = RecordCodecBuilder.create(i -> i.group(
            Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Placed::dimension),
            BlockPos.CODEC.fieldOf("pos").forGetter(Placed::pos)
        ).apply(i, Placed::new));

        public Placed {
            pos = pos.immutable();
        }

        public GlobalPos toGlobal() {
            return GlobalPos.of(dimension, pos);
        }
    }

    /** @param computerId The CC computer id of the pocket computer or turtle. */
    record Mobile(int computerId) implements HostKey {
        static final Codec<Mobile> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("computer").forGetter(Mobile::computerId)
        ).apply(i, Mobile::new));
    }

    Codec<HostKey> CODEC = Codec.either(Placed.CODEC, Mobile.CODEC).xmap(
        e -> e.map(p -> p, m -> m),
        k -> k instanceof Placed p ? Either.left(p) : Either.right((Mobile) k));

    static Placed of(GlobalPos pos) {
        return new Placed(pos.dimension(), pos.pos());
    }
}
