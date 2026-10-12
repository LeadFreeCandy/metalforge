package io.github.leadfreecandy.metalforge.mixin.alpha_const;

import net.caffeinemc.mods.sodium.client.gl.shader.ShaderConstants;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Every Sodium terrain quad's material is chosen per layer (DefaultMaterials.forChunkLayer), so the alpha cutoff is a
 * per-pass constant: 0.5 in CUTOUT, 0.01 in TRANSLUCENT. Bake it in as a define; the shader patch (AlphaConst) then
 * drops the flat v_Material varying and the bit-unpack + const-array lookup per fragment. Identical output.
 */
@Mixin(value = ShaderChunkRenderer.class, remap = false)
public abstract class ShaderChunkRendererMixin {
    @Redirect(method = "createShaderConstants", at = @At(value = "INVOKE",
        target = "Lnet/caffeinemc/mods/sodium/client/gl/shader/ShaderConstants$Builder;build()Lnet/caffeinemc/mods/sodium/client/gl/shader/ShaderConstants;"))
    private static ShaderConstants metalforge$alphaCutoff(ShaderConstants.Builder builder, ChunkShaderOptions options) {
        builder.add("METALFORGE_ALPHA_CUTOFF", options.pass().isTranslucent() ? "0.01" : "0.5");
        return builder.build();
    }
}
