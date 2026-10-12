package io.github.leadfreecandy.metalforge.metal.mixin.sodium;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuSampler;
import io.github.leadfreecandy.metalforge.metal.MetalSodium;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlTexelBuffer;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.device.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.gl.device.RenderDevice;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderConstants;
import net.caffeinemc.mods.sodium.client.gl.tessellation.GlTessellation;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.SharedQuadIndexBuffer;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** On the Metal backend, Sodium's terrain is drawn by MetalSodium.render (Sodium's own batching, Metal encoding). */
@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class DefaultChunkRendererMixin extends ShaderChunkRenderer implements MetalSodium.RendererAccess {
	@Shadow @Final private SharedQuadIndexBuffer sharedIndexBuffer;

	private DefaultChunkRendererMixin(RenderDevice device, ChunkVertexType vertexType) {
		super(device, vertexType);
	}

	@Shadow
	private static void fillCommandBuffer(MultiDrawBatch batch, RenderRegion renderRegion, SectionRenderDataStorage renderDataStorage,
		ChunkRenderList renderList, CameraTransform camera, TerrainRenderPass pass, boolean useBlockFaceCulling, boolean useIndexedTessellation) {
		throw new AssertionError();
	}

	@Shadow
	private GlTessellation prepareTessellation(CommandList commandList, RenderRegion.DeviceResources resources) {
		throw new AssertionError();
	}

	@Shadow
	private GlTessellation prepareIndexedTessellation(CommandList commandList, RenderRegion.DeviceResources resources) {
		throw new AssertionError();
	}

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void metalforge$render(ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass,
		CameraTransform camera, FogParameters parameters, boolean indexedRenderingEnabled, GpuSampler terrainSampler, GpuBufferSlice uniformBuffer,
		GlTexelBuffer sectionTimeInfoTexture, CallbackInfo ci) {
		if (!MetalSodium.active()) return;
		MetalSodium.render(this, matrices, renderLists, renderPass, camera, parameters, indexedRenderingEnabled, terrainSampler, uniformBuffer, sectionTimeInfoTexture);
		ci.cancel();
	}

	@Override
	public void metalforge$ensureSharedCapacity(CommandList commandList, int elementCount) {
		this.sharedIndexBuffer.ensureCapacity(commandList, elementCount);
	}

	@Override
	public GlTessellation metalforge$tessellation(CommandList commandList, RenderRegion.DeviceResources resources, boolean indexed) {
		return indexed ? this.prepareIndexedTessellation(commandList, resources) : this.prepareTessellation(commandList, resources);
	}

	@Override
	public void metalforge$fill(MultiDrawBatch batch, RenderRegion region, SectionRenderDataStorage storage, ChunkRenderList renderList, CameraTransform camera,
		TerrainRenderPass pass, boolean faceCulling, boolean indexed) {
		fillCommandBuffer(batch, region, storage, renderList, camera, pass, faceCulling, indexed);
	}

	@Override
	public ShaderConstants metalforge$constants(ChunkShaderOptions options) {
		return ShaderChunkRendererAccessor.metalforge$createShaderConstants(options);
	}

	@Override
	public ChunkVertexType metalforge$vertexType() {
		return this.vertexType;
	}
}
