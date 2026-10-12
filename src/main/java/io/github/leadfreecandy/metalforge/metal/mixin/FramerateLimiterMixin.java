package io.github.leadfreecandy.metalforge.metal.mixin;

import io.github.leadfreecandy.metalforge.metal.Pacing;
import net.minecraft.client.FramerateLimiter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** With smooth pacing the Metal backend already waited for the next frame's beat (and honours the frame limit there). */
@Mixin(FramerateLimiter.class)
public abstract class FramerateLimiterMixin {
    @Inject(method = "limitDisplayFPS", at = @At("HEAD"), cancellable = true)
    private static void metalforge$smooth(int framerateLimit, CallbackInfo ci) {
        if (Pacing.smooth()) ci.cancel();
    }
}
