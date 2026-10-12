package io.github.leadfreecandy.metalforge.metal.mixin.sodium;

import net.caffeinemc.mods.sodium.client.gl.shader.ShaderConstants;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = ShaderChunkRenderer.class, remap = false)
public interface ShaderChunkRendererAccessor {
	@Invoker("createShaderConstants")
	static ShaderConstants metalforge$createShaderConstants(ChunkShaderOptions options) {
		throw new AssertionError();
	}
}
