package io.github.leadfreecandy.metalforge.mixin.lootr_far;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import noobanidus.mods.lootr.common.api.LootrAPI;
import noobanidus.mods.lootr.common.api.data.ILootrContainerInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lootr's client tick runs for every Lootr container in loaded chunks every tick (3.4% of the render thread in the pack:
 * config reads, type lookups, a line-of-sight raycast) and all it does is spawn particles (unopened / refresh / decay).
 * Vanilla drops any particle spawned more than 32 blocks from the camera (LevelRenderer.addParticleInternal), so for
 * containers farther than that, plus the 1-block spread of Lootr's particles, nothing it spawns would ever exist. Skipped
 * there. (The only difference: those calls no longer draw from the level's random source.)
 */
@Mixin(value = LootrAPI.class, remap = false)
public abstract class LootrClientTickMixin {
    private static final double CUTOFF_SQ = 34.0 * 34.0;
    /** 1 = safe to skip, 0 = a Lootr particle type overrides the limiter (always created at any distance), -1 = unchecked. */
    private static int metalforge$safe = -1;

    /** Every particle type LootrRegistry hands out must obey the 32-block cutoff; otherwise far containers stay ticking. */
    private static boolean metalforge$safe() {
        if (metalforge$safe < 0) {
            int ok = 1;
            try {
                for (java.lang.reflect.Method m : noobanidus.mods.lootr.common.api.LootrRegistry.class.getMethods()) {
                    if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0
                        && net.minecraft.core.particles.ParticleType.class.isAssignableFrom(m.getReturnType())) {
                        Object t = m.invoke(null);
                        if (t instanceof net.minecraft.core.particles.ParticleType<?> pt && pt.getOverrideLimiter()) ok = 0;
                    }
                }
            } catch (Throwable e) {
                ok = 0;
            }
            metalforge$safe = ok;
            org.slf4j.LoggerFactory.getLogger("MetalForge").info("lootr_far: {}", ok == 1 ? "skipping client ticks of Lootr containers beyond particle range"
                : "a Lootr particle type ignores the distance limit; not skipping");
        }
        return metalforge$safe == 1;
    }

    @Inject(method = "handleInstanceClientTick", at = @At("HEAD"), cancellable = true)
    private static void metalforge$far(ILootrContainerInstance instance, CallbackInfo ci) {
        if (instance == null || !metalforge$safe()) return;
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        if (camera == null) return;
        Vec3 c = instance.getParticleCenter();
        if (c != null && camera.position().distanceToSqr(c) > CUTOFF_SQ) ci.cancel();
    }
}
