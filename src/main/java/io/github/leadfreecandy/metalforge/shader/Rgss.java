package io.github.leadfreecandy.metalforge.shader;

import io.github.leadfreecandy.metalforge.Options;

/**
 * Option "rgss": Sodium's RGSS texture filter always takes 4 supersamples + 1 nearest sample per terrain fragment and
 * then mixes them by blendFactor. Where blendFactor is exactly 0 (magnified/near textures: most of the nearby screen)
 * the supersamples are discarded, and where it is exactly 1 the nearest sample is. Branch on it: identical output
 * (mix(a,b,0) == a, mix(a,b,1) == b), fewer texture fetches. Derivatives are computed before the branch and all
 * fetches use explicit LOD/gradients, so the non-uniform branch is safe.
 */
final class Rgss {
    static final String PATH = "sodium:blocks/block_layer_opaque.fsh";

    private Rgss() {}

    static void register() {
        Options.KNOWN.put("rgss", "Skip RGSS supersamples/nearest sample when unused (identical output)");
        ShaderPatches.add("rgss", PATH, src -> {
            String s = ShaderPatches.replaceOnce(src,
                "    float mipLevelExact = max(0.0, log2(effectiveDerivative / minPixelSize));",
                "    if (blendFactor <= 0.0) {\n" +
                "        return sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);\n" +
                "    }\n\n" +
                "    float mipLevelExact = max(0.0, log2(effectiveDerivative / minPixelSize));");
            if (s == null) return null;
            return ShaderPatches.replaceOnce(s,
                "    vec4 nearestColor = sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);",
                "    if (blendFactor >= 1.0) {\n" +
                "        return rgssColor;\n" +
                "    }\n\n" +
                "    vec4 nearestColor = sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);");
        });
    }
}
