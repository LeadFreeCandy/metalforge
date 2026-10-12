package io.github.leadfreecandy.metalforge.mixin.fbo;

import com.mojang.blaze3d.opengl.DirectStateAccess;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import io.github.leadfreecandy.metalforge.opt.FboTracker;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Remembers which FBO carries which depth texture, so depth clears can happen in that FBO (see GlCommandEncoderMixin). */
@Mixin(value = GlTexture.class, remap = false)
public abstract class GlTextureMixin {
    @Inject(method = "getFbo", at = @At("RETURN"))
    private void metalforge$track(DirectStateAccess dsa, @Nullable GpuTexture depth, CallbackInfoReturnable<Integer> cir) {
        if (depth != null) FboTracker.record(cir.getReturnValueI(), ((GlTexture) depth).glId());
    }

    /** GL recycles texture and FBO names: forget everything when any texture (and its FBOs) is destroyed. Rare. */
    @Inject(method = "destroyImmediately", at = @At("HEAD"))
    private void metalforge$forget(CallbackInfo ci) {
        FboTracker.clear();
    }
}
