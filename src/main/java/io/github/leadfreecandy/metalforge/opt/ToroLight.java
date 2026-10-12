package io.github.leadfreecandy.metalforge.opt;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * ToroHealth's in-world bar hook walks every loaded entity each frame and computes its packed light (two block light
 * lookups) before its frustum check and before InWorldBarRenderer.render's own gate (visibility mode, distance, onlyWhenHurt,
 * ...), which rejects nearly every entity. The light is only used by bars actually drawn. Here the light call returns a
 * sentinel and remembers its arguments; the render call runs ToroHealth's gate first and computes the light (same
 * dispatcher, entity and partial tick, same frame, nothing in between changes the level) only for entities that pass.
 */
public final class ToroLight {
    private static final String HANDLER_SUFFIX = "$torohealth$render_AddInWorldBarsToBatch";
    private static final String DISPATCHER = "net/minecraft/client/renderer/entity/EntityRenderDispatcher";
    private static final String BAR_RENDERER = "net/kairost/torohealth/client/render/InWorldBarRenderer";
    private static final String SELF = "io/github/leadfreecandy/metalforge/opt/ToroLight";
    private static final int DEFERRED = Integer.MIN_VALUE; // packed light is 0..0xF000F0, never this

    private static MethodHandle gate, render;
    private static float pendingPartialTick;

    private ToroLight() {}

    /** Rewrites the two calls in the merged handler; returns whether it did. Leaves the class untouched if anything is off. */
    public static boolean patch(ClassNode cls) {
        for (MethodNode m : cls.methods) {
            if (!m.name.endsWith(HANDLER_SUFFIX)) continue;
            MethodInsnNode light = null, draw = null;
            for (AbstractInsnNode n : m.instructions) {
                if (!(n instanceof MethodInsnNode mi)) continue;
                if (mi.getOpcode() == Opcodes.INVOKEVIRTUAL && mi.owner.equals(DISPATCHER) && mi.name.equals("getPackedLightCoords")
                    && mi.desc.equals("(Lnet/minecraft/world/entity/Entity;F)I")) light = light == null ? mi : null;
                if (mi.getOpcode() == Opcodes.INVOKESTATIC && mi.owner.equals(BAR_RENDERER) && mi.name.equals("render")) draw = draw == null ? mi : null;
            }
            if (light == null || draw == null || !draw.desc.equals("(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/Camera;FI" + "L" + DISPATCHER + ";)V")) return false;
            m.instructions.set(light, new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "light", "(L" + DISPATCHER + ";Lnet/minecraft/world/entity/Entity;F)I", false));
            m.instructions.set(draw, new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "render", draw.desc, false));
            return true;
        }
        return false;
    }

    /** Resolved on first use, not while LevelRenderer is being transformed. Without access to the private gate the light is
     * computed for every entity, as ToroHealth does. */
    private static void resolve() throws ReflectiveOperationException {
        Class<?> c = Class.forName(BAR_RENDERER.replace('/', '.'));
        render = MethodHandles.publicLookup().findStatic(c, "render",
            MethodType.methodType(void.class, Entity.class, Camera.class, float.class, int.class, EntityRenderDispatcher.class));
        try {
            gate = MethodHandles.privateLookupIn(c, MethodHandles.lookup()).findStatic(c, "shouldRender",
                MethodType.methodType(boolean.class, Entity.class, EntityRenderDispatcher.class));
        } catch (ReflectiveOperationException | RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger("MetalForge").warn("toro_light: no access to InWorldBarRenderer.shouldRender ({}), light not deferred", e.toString());
        }
    }

    public static int light(EntityRenderDispatcher dispatcher, Entity entity, float partialTick) {
        pendingPartialTick = partialTick;
        return DEFERRED;
    }

    public static void render(Entity entity, Camera camera, float partialTick, int light, EntityRenderDispatcher dispatcher) throws Throwable {
        if (render == null) resolve();
        if (light == DEFERRED) {
            if (gate != null && !(boolean) gate.invokeExact(entity, dispatcher)) return;
            light = dispatcher.getPackedLightCoords(entity, pendingPartialTick);
        }
        render.invokeExact(entity, camera, partialTick, light, dispatcher);
    }
}
