package dev.ccwp.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Optional;

/**
 * A wireless link from a host to a peripheral block. The block's name and type are stored per block in
 * {@link LinkSavedData}, shared by every host that links it.
 *
 * @param target The peripheral block. For placed hosts it is always in the host's dimension. A mobile host can have
 *               links in several dimensions; only those in its current dimension are reachable.
 * @param face   The face that was clicked; used as the capability side when looking up the peripheral.
 */
public record Link(GlobalPos target, Direction face) {
    public BlockPos pos() {
        return target.pos();
    }

    public ResourceKey<Level> dimension() {
        return target.dimension();
    }

    /**
     * The stored form of a link. Saves from before per-block names have no dimension (it is the host's) and carry the
     * link's own type and name, which are merged into the per-block names on load.
     */
    record Stored(Optional<ResourceKey<Level>> dimension, BlockPos pos, Direction face,
                  Optional<String> type, Optional<String> name, boolean custom) {
        static final Codec<Stored> CODEC = RecordCodecBuilder.create(i -> i.group(
            Level.RESOURCE_KEY_CODEC.optionalFieldOf("dimension").forGetter(Stored::dimension),
            BlockPos.CODEC.fieldOf("pos").forGetter(Stored::pos),
            Direction.CODEC.fieldOf("face").forGetter(Stored::face),
            Codec.STRING.optionalFieldOf("type").forGetter(Stored::type),
            Codec.STRING.optionalFieldOf("name").forGetter(Stored::name),
            Codec.BOOL.optionalFieldOf("custom", false).forGetter(Stored::custom)
        ).apply(i, Stored::new));

        static Stored of(Link link) {
            return new Stored(Optional.of(link.dimension()), link.pos(), link.face(), Optional.empty(), Optional.empty(), false);
        }
    }
}
