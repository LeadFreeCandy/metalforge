package io.github.leadfreecandy.metalforge.mixin.outline;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla clears the full-screen entity-outline target and blends it over the main image every frame, even when no
 * entity glows (the common case). With nothing drawn into it the target stays all zeros and the blend
 * (SRC_ALPHA, ONE_MINUS_SRC_ALPHA with alpha 0) leaves the image bit-identical. Skip both while the target is
 * known clean: saves a full-screen clear, two framebuffer switches and a full-screen sampled blend pass per frame.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererOutlineMixin {
    @Shadow private @Nullable RenderTarget entityOutlineTarget;
    @Shadow @Final LevelRenderState levelRenderState;

    /** Whether the outline target may contain anything (unknown contents count as dirty). */
    @Unique private boolean metalforge$outlineDirty = true;

    @Redirect(method = "*", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearColorAndDepthTextures(Lcom/mojang/blaze3d/textures/GpuTexture;ILcom/mojang/blaze3d/textures/GpuTexture;D)V"))
    private void metalforge$maybeClear(CommandEncoder encoder, GpuTexture color, int clearColor, GpuTexture depth, double clearDepth) {
        boolean outline = this.entityOutlineTarget != null && color == this.entityOutlineTarget.getColorTexture();
        if (!outline) {
            encoder.clearColorAndDepthTextures(color, clearColor, depth, clearDepth);
            return;
        }
        boolean glowing = this.levelRenderState.haveGlowingEntities;
        if (glowing || this.metalforge$outlineDirty) encoder.clearColorAndDepthTextures(color, clearColor, depth, clearDepth);
        this.metalforge$outlineDirty = glowing;
    }

    @Inject(method = "doEntityOutline", at = @At("HEAD"), cancellable = true)
    private void metalforge$skipCleanBlend(CallbackInfo ci) {
        if (!this.metalforge$outlineDirty) ci.cancel();
    }

    @Inject(method = "initOutline", at = @At("RETURN"))
    private void metalforge$newTarget(CallbackInfo ci) {
        this.metalforge$outlineDirty = true;
    }
}
