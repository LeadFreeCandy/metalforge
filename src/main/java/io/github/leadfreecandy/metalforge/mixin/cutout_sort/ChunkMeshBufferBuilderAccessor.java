package io.github.leadfreecandy.metalforge.mixin.cutout_sort;

import java.lang.foreign.MemorySegment;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.builder.ChunkMeshBufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ChunkMeshBufferBuilder.class, remap = false)
public interface ChunkMeshBufferBuilderAccessor {
    @Accessor("buffer")
    MemorySegment metalforge$buffer();

    @Accessor("stride")
    int metalforge$stride();
}
