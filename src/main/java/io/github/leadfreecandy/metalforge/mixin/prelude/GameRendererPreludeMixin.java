package io.github.leadfreecandy.metalforge.mixin.prelude;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import io.github.leadfreecandy.metalforge.opt.IrisCompat;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GameRenderer.render clears the main target, then (on tick frames) renders the lightmap into its own texture, then
 * the level's frame graph clears the main target again, fully, with the fog colour. When the level is rendered the
 * first clear is dead: drop it. That removes a full-screen clear and, on tick frames, a main -> lightmap -> main
 * render-target round trip. Kept whenever the level isn't rendered (menus, loading) or an Iris shader pack is active.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererPreludeMixin {
    @Shadow @Final private Minecraft minecraft;
    @Unique private boolean metalforge$advanceGameTime;

    @Inject(method = "render", at = @At("HEAD"))
    private void metalforge$captureArgs(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        this.metalforge$advanceGameTime = advanceGameTime;
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", ordinal = 0,
        target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearColorAndDepthTextures(Lcom/mojang/blaze3d/textures/GpuTexture;ILcom/mojang/blaze3d/textures/GpuTexture;D)V"))
    private void metalforge$skipDeadClear(CommandEncoder encoder, GpuTexture color, int clearColor, GpuTexture depth, double clearDepth) {
        boolean levelWillClear = this.metalforge$advanceGameTime && this.minecraft.level != null && this.minecraft.isGameLoadFinished()
            && !IrisCompat.shaderPackInUse();
        if (!levelWillClear) encoder.clearColorAndDepthTextures(color, clearColor, depth, clearDepth);
    }
}
