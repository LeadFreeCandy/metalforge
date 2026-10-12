package io.github.leadfreecandy.metalforge.shader;

import io.github.leadfreecandy.metalforge.Options;

/** Option "alpha_const" (shader side; Java side in mixin.alpha_const): see ShaderChunkRendererMixin. */
final class AlphaConst {
    private AlphaConst() {}

    static void register() {
        Options.KNOWN.put("alpha_const", "Per-pass constant alpha cutoff in Sodium terrain shaders, no v_Material varying (identical)");
        ShaderPatches.add("alpha_const", Rgss.PATH, src -> {
            String s = ShaderPatches.replaceOnce(src, "flat in uint v_Material;", "");
            if (s == null) return null;
            return ShaderPatches.replaceOnce(s, "color.a < _material_alpha_cutoff(v_Material)", "color.a < METALFORGE_ALPHA_CUTOFF");
        });
        ShaderPatches.add("alpha_const", "sodium:blocks/block_layer_opaque.vsh", src -> {
            String s = ShaderPatches.replaceOnce(src, "flat out uint v_Material;", "");
            if (s == null) return null;
            return ShaderPatches.replaceOnce(s, "    v_Material = _material_params;", "");
        });
    }
}
