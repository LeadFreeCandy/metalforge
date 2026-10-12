package io.github.leadfreecandy.metalforge.opt;

import java.util.Arrays;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import org.lwjgl.system.MemoryUtil;

/** In-place stable front-to-back sort of a Sodium compact-vertex quad buffer for one axis facing (chunk builder threads). */
public final class CutoutSort {
    private static final ThreadLocal<long[]> KEYS = ThreadLocal.withInitial(() -> new long[4096]);

    private CutoutSort() {}

    /** 20-bit quantized coordinate on axis (0=x,1=y,2=z) of the vertex at p (CompactChunkVertex position words). */
    private static int coord(long p, int axis) {
        int hi = MemoryUtil.memGetInt(p) >>> (axis * 10) & 0x3FF;
        int lo = MemoryUtil.memGetInt(p + 4) >>> (axis * 10) & 0x3FF;
        return (hi << 10) | lo;
    }

    public static void sort(long base, int quads, int stride, ModelQuadFacing facing) {
        int axis;
        boolean positive;
        switch (facing) {
            case POS_X -> { axis = 0; positive = true; }
            case POS_Y -> { axis = 1; positive = true; }
            case POS_Z -> { axis = 2; positive = true; }
            case NEG_X -> { axis = 0; positive = false; }
            case NEG_Y -> { axis = 1; positive = false; }
            case NEG_Z -> { axis = 2; positive = false; }
            default -> { return; }
        }
        long[] keys = KEYS.get();
        if (keys.length < quads) KEYS.set(keys = new long[Math.max(quads, keys.length * 2)]);
        int quadBytes = stride * 4;
        boolean sorted = true;
        long prev = Long.MIN_VALUE;
        for (int q = 0; q < quads; q++) {
            long p = base + (long) q * quadBytes;
            // plane position: sum over the 4 vertices (exact for axis-aligned quads, sensible for slightly tilted ones)
            long c = coord(p, axis) + coord(p + stride, axis) + coord(p + 2L * stride, axis) + coord(p + 3L * stride, axis);
            long k = positive ? (1L << 23) - c : c;       // POS faces: farther along +normal is nearer the viewer
            keys[q] = (k << 32) | q;                      // index in low bits: stable
            if (keys[q] < prev) sorted = false;
            prev = keys[q];
        }
        if (sorted) return;
        Arrays.sort(keys, 0, quads);
        long bytes = (long) quads * quadBytes;
        long tmp = MemoryUtil.nmemAlloc(bytes);
        try {
            for (int i = 0; i < quads; i++) {
                int src = (int) keys[i];
                MemoryUtil.memCopy(base + (long) src * quadBytes, tmp + (long) i * quadBytes, quadBytes);
            }
            MemoryUtil.memCopy(tmp, base, bytes);
        } finally {
            MemoryUtil.nmemFree(tmp);
        }
    }
}
