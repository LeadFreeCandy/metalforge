package io.github.leadfreecandy.metalforge.mixin.fbo;

import com.mojang.blaze3d.opengl.DirectStateAccess;
import com.mojang.blaze3d.opengl.GlTextureView;
import com.mojang.blaze3d.textures.GpuTexture;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A mip-0 view and its texture used to get separate FBOs (separate caches) for identical attachments, so the frame
 * ping-ponged between two FBO names for the main target (clears + Sodium on one, vanilla passes on the other). On Apple's
 * GL-on-Metal each switch can end the Metal render pass (full-screen store + load). Share the texture's FBO instead.
 */
@Mixin(value = GlTextureView.class, remap = false)
public abstract class GlTextureViewMixin {
    @Inject(method = "getFbo", at = @At("HEAD"), cancellable = true)
    private void metalforge$shareTextureFbo(DirectStateAccess dsa, @Nullable GpuTexture depth, CallbackInfoReturnable<Integer> cir) {
        GlTextureView self = (GlTextureView) (Object) this;
        if (self.baseMipLevel() == 0) cir.setReturnValue(self.texture().getFbo(dsa, depth));
    }
}
