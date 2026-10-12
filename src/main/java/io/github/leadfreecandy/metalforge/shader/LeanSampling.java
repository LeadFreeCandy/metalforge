package io.github.leadfreecandy.metalforge.shader;

import com.mojang.blaze3d.textures.FilterMode;
import io.github.leadfreecandy.metalforge.MetalForge;
import io.github.leadfreecandy.metalforge.Options;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;

/**
 * Option "lean_sampling": with Sodium's pixel filtering mode NEAREST (the default, and this pack's setting) the block
 * atlas sampler is MAG=NEAREST / MIN=NEAREST_MIPMAP_LINEAR, so Sodium's sub-texel "snap" in sampleNearest (2-4
 * divides, round, clamp) never changes which texel is fetched: plain gradient sampling is equivalent. The RGSS path
 * keeps the same 4 taps in the same order; the LOD is computed with an algebraically identical, cheaper formula
 * (sqrt(min*max) of the two derivative lengths == (|du|^2 |dv|^2)^(1/4)) and the blend threshold is compared squared.
 * Subsumes the "rgss" early-outs. Only applied when the filter mode is NEAREST at shader load.
 */
final class LeanSampling {
    private LeanSampling() {}

    static final String BODY = """
        // metalforge lean_sampling (NEAREST atlas sampler): see io.github.leadfreecandy.metalforge.shader.LeanSampling
        vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize) {
            return textureGrad(source, uv, dFdx(uv), dFdy(uv));
        }

        vec4 sampleRGSS(sampler2D source, vec2 uv, vec2 pixelSize) {
            vec2 du = dFdx(uv);
            vec2 dv = dFdy(uv);
            vec2 tss2 = du * du + dv * dv;
            float maxTexelSize2 = max(tss2.x, tss2.y);
            float minPixelSize = min(pixelSize.x, pixelSize.y);

            if (maxTexelSize2 <= minPixelSize * minPixelSize) {
                return textureGrad(source, uv, du, dv);
            }

            float blendFactor = smoothstep(minPixelSize, minPixelSize * 2.0, sqrt(maxTexelSize2));
            float mipLevelExact = max(0.0, 0.25 * log2(dot(du, du) * dot(dv, dv)) - log2(minPixelSize));

            vec4 rgssColor = vec4(0.0);
            rgssColor += textureLod(source, uv + vec2( 0.125,  0.375) * pixelSize, mipLevelExact);
            rgssColor += textureLod(source, uv + vec2(-0.125, -0.375) * pixelSize, mipLevelExact);
            rgssColor += textureLod(source, uv + vec2( 0.375, -0.125) * pixelSize, mipLevelExact);
            rgssColor += textureLod(source, uv + vec2(-0.375,  0.125) * pixelSize, mipLevelExact);
            rgssColor *= 0.25;

            if (blendFactor >= 1.0) {
                return rgssColor;
            }
            return mix(textureGrad(source, uv, du, dv), rgssColor, blendFactor);
        }

        """;

    static void register() {
        Options.KNOWN.put("lean_sampling", "Cheaper terrain sampling for Sodium's NEAREST atlas sampler (identical texels)");
        ShaderPatches.add("lean_sampling", Rgss.PATH, src -> {
            if (SodiumClientMod.options().quality.pixelFilteringMode != FilterMode.NEAREST) {
                MetalForge.LOG.info("MetalForge lean_sampling: pixel filtering is not NEAREST, not applied");
                return src;
            }
            int a = src.indexOf("vec4 sampleNearest(sampler2D sampler, vec2 uv, vec2 pixelSize, vec2 du, vec2 dv, vec2 texelScreenSize) {");
            int b = src.indexOf("void main()");
            if (a < 0 || b < a) return null;
            return src.substring(0, a) + BODY + src.substring(b);
        });
    }
}
