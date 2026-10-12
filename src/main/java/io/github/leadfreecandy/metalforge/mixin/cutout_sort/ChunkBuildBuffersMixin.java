package io.github.leadfreecandy.metalforge.mixin.cutout_sort;

import io.github.leadfreecandy.metalforge.opt.CutoutSort;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.buffers.BakedChunkModelBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cutout (alpha-tested) fragments lose Apple's hidden-surface removal: each one that passes the early depth test is
 * shaded, in submission order. Sodium meshes blocks y/z/x-ascending, so e.g. top faces of a leaf canopy are drawn
 * bottom layer first (back to front). Sort each facing's quads front-to-back along the facing normal at mesh build
 * time; for same-facing quads that order is front-to-back from every camera position that can see them. Output is
 * identical except at exact depth ties, and the sort is stable so coplanar quads (overlays) keep their order.
 */
@Mixin(value = ChunkBuildBuffers.class, remap = false)
public abstract class ChunkBuildBuffersMixin {
    @Shadow @Final private Reference2ReferenceOpenHashMap<TerrainRenderPass, BakedChunkModelBuilder> builders;

    @Inject(method = "createMesh", at = @At("HEAD"))
    private void metalforge$sortCutout(TerrainRenderPass pass, int visibleSlices, boolean forceUnassigned, boolean sliceReordering,
                                    CallbackInfoReturnable<BuiltSectionMeshParts> cir) {
        if (pass != DefaultTerrainRenderPasses.CUTOUT) return;
        BakedChunkModelBuilder builder = this.builders.get(pass);
        for (ModelQuadFacing facing : ModelQuadFacing.VALUES) {
            if (facing == ModelQuadFacing.UNASSIGNED) continue;
            var buf = builder.getVertexBuffer(facing);
            if (buf.count() < 8) continue;
            var acc = (ChunkMeshBufferBuilderAccessor) buf;
            CutoutSort.sort(acc.metalforge$buffer().address(), buf.count() >> 2, acc.metalforge$stride(), facing);
        }
    }
}
