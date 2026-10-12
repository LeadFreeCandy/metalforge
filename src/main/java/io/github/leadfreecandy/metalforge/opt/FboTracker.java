package io.github.leadfreecandy.metalforge.opt;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;

/** fbo -> depth texture id, and depth texture id -> most recently requested FBO using it (render thread only). */
public final class FboTracker {
    private static final Int2IntOpenHashMap fboDepth = new Int2IntOpenHashMap();
    private static final Int2IntOpenHashMap depthFbo = new Int2IntOpenHashMap();

    private FboTracker() {}

    public static void record(int fbo, int depthId) {
        fboDepth.put(fbo, depthId);
        depthFbo.put(depthId, fbo);
    }

    /** An FBO that has depthId attached: the bound one if it does, else the last one created/used with it; 0 if none. */
    public static int fboWithDepth(int bound, int depthId) {
        if (bound > 0 && fboDepth.get(bound) == depthId) return bound;
        return depthFbo.get(depthId);
    }

    public static void clear() {
        fboDepth.clear();
        depthFbo.clear();
    }
}
