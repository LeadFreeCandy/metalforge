package io.github.leadfreecandy.metalforge.opt;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Is an Iris shader pack active? (Iris is optional; reflection, resolved once.) Shader packs replace the pipeline,
 *  so frame-structure optimizations step aside while one is in use. */
public final class IrisCompat {
    private static final MethodHandle IN_USE;

    static {
        MethodHandle h = null;
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object inst = api.getMethod("getInstance").invoke(null);
            h = MethodHandles.publicLookup().findVirtual(api, "isShaderPackInUse", MethodType.methodType(boolean.class)).bindTo(inst);
        } catch (Throwable ignored) {
        }
        IN_USE = h;
    }

    private IrisCompat() {}

    public static boolean shaderPackInUse() {
        if (IN_USE == null) return false;
        try {
            return (boolean) IN_USE.invokeExact();
        } catch (Throwable t) {
            return true;   // be conservative
        }
    }
}
