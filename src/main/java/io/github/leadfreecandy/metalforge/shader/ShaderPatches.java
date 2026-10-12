package io.github.leadfreecandy.metalforge.shader;

import io.github.leadfreecandy.metalforge.MetalForge;
import io.github.leadfreecandy.metalforge.Options;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Composable text patches to Sodium's GLSL sources (applied by ShaderLoaderMixin). Each optimization registers its own
 * patch(es) only when enabled; patches are applied in registration order. A patch whose anchor text isn't found logs a
 * warning and leaves the source unchanged (never half-applies).
 */
public final class ShaderPatches {
    private record Patch(String opt, String path, UnaryOperator<String> fn) {}

    private static final List<Patch> PATCHES = new ArrayList<>();

    static {
        Rgss.register();
        LeanSampling.register();
        AlphaConst.register();
    }

    private ShaderPatches() {}

    public static void add(String opt, String path, UnaryOperator<String> fn) {
        if (Options.enabled(opt)) PATCHES.add(new Patch(opt, path, fn));
    }

    public static String apply(String path, String src) {
        String out = src;
        for (Patch p : PATCHES) {
            if (!p.path.equals(path)) continue;
            String next = p.fn.apply(out);
            if (next == null) MetalForge.LOG.warn("MetalForge shader patch {} did not match {}; left unchanged", p.opt, path);
            else out = next;
        }
        return out;
    }

    /** Replace exactly one occurrence of anchor, or return null (patch doesn't apply). */
    public static String replaceOnce(String src, String anchor, String replacement) {
        int i = src.indexOf(anchor);
        if (i < 0 || src.indexOf(anchor, i + 1) >= 0) return null;
        return src.substring(0, i) + replacement + src.substring(i + anchor.length());
    }
}
