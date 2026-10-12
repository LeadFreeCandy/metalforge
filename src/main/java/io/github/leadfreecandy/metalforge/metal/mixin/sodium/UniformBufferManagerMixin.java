package io.github.leadfreecandy.metalforge.metal.mixin.sodium;

import com.mojang.blaze3d.buffers.GpuBuffer;
import io.github.leadfreecandy.metalforge.metal.MetalSodium;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlTexelBuffer;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The section-time texel buffer is a game GpuBuffer that Sodium views through a GL texture made from the buffer's GL name;
 * on Metal the buffer has no GL name (0), and MetalSodium binds a Metal texel view of the same buffer instead.
 */
@Mixin(value = UniformBufferManager.class, remap = false)
public abstract class UniformBufferManagerMixin {
	@Shadow private GpuBuffer sectionTimeInfo;
	@Shadow private GlTexelBuffer sectionTimeInfoTexture;

	@Inject(method = {"<init>", "resizeIfNeeded"}, at = @At("RETURN"))
	private void metalforge$register(CallbackInfo ci) {
		if (MetalSodium.active() && this.sectionTimeInfoTexture != null) MetalSodium.registerTexelBuffer(this.sectionTimeInfoTexture, this.sectionTimeInfo);
	}
}
