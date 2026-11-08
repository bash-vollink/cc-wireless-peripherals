package dev.ccwp.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceKey;

/** Identifies a host block (wireless/ender modem, CC: Modern server, ...) by dimension and position. */
public record HostKey(ResourceKey<Level> dimension, BlockPos pos) {
    public static final Codec<HostKey> CODEC = RecordCodecBuilder.create(i -> i.group(
        Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(HostKey::dimension),
        BlockPos.CODEC.fieldOf("pos").forGetter(HostKey::pos)
    ).apply(i, HostKey::new));

    public HostKey {
        pos = pos.immutable();
    }

    public static HostKey of(GlobalPos pos) {
        return new HostKey(pos.dimension(), pos.pos());
    }

    public GlobalPos toGlobal() {
        return GlobalPos.of(dimension, pos);
    }
}
