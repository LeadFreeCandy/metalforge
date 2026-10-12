package io.github.leadfreecandy.metalforge.mixin.fbo;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import io.github.leadfreecandy.metalforge.opt.FboTracker;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keep the frame on as few framebuffer switches as possible:
 *  - depth-only clears (before the hand, before the GUI) happen in the FBO that already holds that depth texture
 *    (normally the bound main target) instead of re-attaching it to a scratch depth-only FBO and back;
 *  - no "bind framebuffer 0" after clears and render passes: every pass binds its own target anyway, and the
 *    intermediate unbind is another target switch.
 * Pixels are identical: same clears, same draws, same targets.
 */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder", remap = false)
public abstract class GlCommandEncoderMixin {
    @Inject(method = "clearDepthTexture", at = @At("HEAD"), cancellable = true)
    private void metalforge$clearDepthInPlace(GpuTexture depthTexture, double clearDepth, CallbackInfo ci) {
        int fbo = FboTracker.fboWithDepth(GlStateManager.getFrameBuffer(36009), ((GlTexture) depthTexture).glId());
        if (fbo <= 0) return;
        GlStateManager._glBindFramebuffer(36160, fbo);
        GL11.glClearDepth(clearDepth);
        GlStateManager._depthMask(true);
        GlStateManager._disableScissorTest();
        GlStateManager._clear(256);
        ci.cancel();
    }

    @Redirect(method = {"clearColorAndDepthTextures", "clearDepthTexture", "finishRenderPass"},
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_glBindFramebuffer(II)V"), require = 1)
    private void metalforge$noUnbind(int target, int fbo) {
        if (fbo != 0) GlStateManager._glBindFramebuffer(target, fbo);
    }
}
