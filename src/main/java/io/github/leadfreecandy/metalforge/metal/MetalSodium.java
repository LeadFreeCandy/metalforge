package io.github.leadfreecandy.metalforge.metal;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBuffer;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferMapFlags;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferMapping;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferStorageFlags;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferTarget;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferUsage;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlImmutableBuffer;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlMutableBuffer;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlTexelBuffer;
import net.caffeinemc.mods.sodium.client.gl.array.GlVertexArray;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.device.DrawCommandList;
import net.caffeinemc.mods.sodium.client.gl.device.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderConstants;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderParser;
import net.caffeinemc.mods.sodium.client.gl.sync.GlFence;
import net.caffeinemc.mods.sodium.client.gl.tessellation.GlIndexType;
import net.caffeinemc.mods.sodium.client.gl.tessellation.GlPrimitiveType;
import net.caffeinemc.mods.sodium.client.gl.tessellation.GlTessellation;
import net.caffeinemc.mods.sodium.client.gl.tessellation.TessellationBinding;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkFogMode;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.caffeinemc.mods.sodium.client.util.UInt32;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/**
 * Sodium 0.9.2 on the Metal backend. Sodium keeps everything CPU-side (meshing, culling, region arenas, batching); only its GPU
 * layer changes:
 * <ul>
 * <li>{@link Commands}: Sodium's CommandList with every GlBuffer backed by a shared-storage MTLBuffer (uploads are memcpys
 * into idle memory, otherwise staged GPU copies in order).</li>
 * <li>{@link #render}: DefaultChunkRenderer.render on Metal: one render pass on the terrain target, Sodium's own GLSL compiled
 * through MetalPipeline (its loose uniforms gathered into a block), and the region batches Sodium already builds encoded as
 * indexed draws.</li>
 * </ul>
 * Mixins in mixin/sodium route Sodium here while the Metal device is active (MetalDevice.active != null).
 */
public final class MetalSodium {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final Map<GlBuffer, MetalBuffer> BUFFERS = new IdentityHashMap<>();
	/** Section time texel buffers: Sodium's GL texture object -> the game buffer it views (UniformBufferManager). */
	private static final Map<GlTexelBuffer, GpuBuffer> TEXELS = new IdentityHashMap<>();
	private static final Map<String, MetalPipeline> PIPELINES = new HashMap<>();
	private static final Commands COMMANDS = new Commands();

	private MetalSodium() {
	}

	public static boolean active() {
		return MetalDevice.active != null;
	}

	public static CommandList commandList() {
		return COMMANDS;
	}

	/** UniformBufferManager's GL-name lookup of a game buffer (patched in by SodiumPlugin): no GL names on Metal. */
	public static int glHandle(Object buffer) {
		return active() ? 0 : ((net.caffeinemc.mods.sodium.mixin.core.GlBufferAccessor) buffer).sodium$getHandle();
	}

	public static void registerTexelBuffer(GlTexelBuffer texture, GpuBuffer buffer) {
		TEXELS.put(texture, buffer);
	}

	private static MetalDevice device() {
		return MetalDevice.active;
	}

	private static MetalBuffer buffer(GlBuffer gl) {
		MetalBuffer b = BUFFERS.get(gl);
		if (b == null) throw new IllegalStateException("Sodium buffer with no Metal storage");
		return b;
	}

	private static MetalBuffer allocate(GlBuffer gl, long size) {
		MetalDevice d = device();
		MetalBuffer old = BUFFERS.get(gl);
		if (old != null) old.close();
		MetalBuffer b = new MetalBuffer(d.encoder, Native.bufferNew(d.ctx, Math.max(16, size)), GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_INDEX, size);
		BUFFERS.put(gl, b);
		return b;
	}

	/** Sodium's CommandList / DrawCommandList on Metal memory. */
	static final class Commands implements CommandList, DrawCommandList {
		@Override
		public GlMutableBuffer createMutableBuffer() {
			return new GlMutableBuffer();
		}

		@Override
		public GlImmutableBuffer createImmutableBuffer(long bufferSize, net.caffeinemc.mods.sodium.client.gl.util.EnumBitField<GlBufferStorageFlags> flags) {
			GlImmutableBuffer b = new GlImmutableBuffer(flags);
			allocate(b, bufferSize);
			return b;
		}

		@Override
		public GlTessellation createTessellation(GlPrimitiveType primitiveType, TessellationBinding[] bindings) {
			return new Tessellation(primitiveType, bindings);
		}

		@Override
		public void bindVertexArray(GlVertexArray array) {
		}

		@Override
		public void uploadData(GlMutableBuffer glBuffer, ByteBuffer byteBuffer, GlBufferUsage usage) {
			MetalBuffer b = allocate(glBuffer, byteBuffer.remaining());
			MemoryUtil.memCopy(MemoryUtil.memAddress(byteBuffer), b.address, byteBuffer.remaining());
			glBuffer.setSize(byteBuffer.remaining());
		}

		@Override
		public void uploadDataToOffset(GlMutableBuffer glBuffer, int offset, long pointer, int size) {
			device().encoder.writeRaw(buffer(glBuffer), offset, pointer, size);
		}

		@Override
		public void copyBufferSubData(GlBuffer src, GlBuffer dst, long readOffset, long writeOffset, long bytes) {
			device().encoder.copyRaw(buffer(src), readOffset, buffer(dst), writeOffset, bytes);
		}

		@Override
		public void bindBuffer(GlBufferTarget target, GlBuffer buffer) {
		}

		@Override
		public void unbindVertexArray() {
		}

		@Override
		public void allocateStorage(GlMutableBuffer glBuffer, long bufferSize, GlBufferUsage usage) {
			allocate(glBuffer, bufferSize);
			glBuffer.setSize(bufferSize);
		}

		@Override
		public void deleteBuffer(GlBuffer glBuffer) {
			if (glBuffer.getActiveMapping() != null) this.unmap(glBuffer.getActiveMapping());
			MetalBuffer b = BUFFERS.remove(glBuffer);
			if (b != null) b.close();
			glBuffer.invalidateHandle();
		}

		@Override
		public void deleteVertexArray(GlVertexArray vertexArray) {
		}

		@Override
		public void flush() {
		}

		@Override
		public DrawCommandList beginTessellating(GlTessellation tessellation) {
			return this;
		}

		@Override
		public void deleteTessellation(GlTessellation tessellation) {
		}

		@Override
		public GlBufferMapping mapBuffer(GlBuffer glBuffer, long offset, long length, net.caffeinemc.mods.sodium.client.gl.util.EnumBitField<GlBufferMapFlags> flags) {
			if (glBuffer.getActiveMapping() != null) throw new IllegalStateException("Buffer is already mapped");
			MetalBuffer b = buffer(glBuffer);
			GlBufferMapping mapping = new GlBufferMapping(glBuffer, MemoryUtil.memByteBuffer(b.address + offset, Math.toIntExact(length)));
			glBuffer.setActiveMapping(mapping);
			return mapping;
		}

		@Override
		public void unmap(GlBufferMapping map) {
			map.getBufferObject().setActiveMapping(null);
			map.dispose();
		}

		@Override
		public void flushMappedRange(GlBufferMapping map, int offset, int length) {
		}

		@Override
		public GlFence createFence() {
			throw new UnsupportedOperationException("Sodium fences aren't used on macOS (no persistent staging); not supported on Metal");
		}

		// DrawCommandList: the Metal renderer encodes draws itself (MetalSodium.render)
		@Override
		public void multiDrawElementsBaseVertex(MultiDrawBatch batch, GlIndexType indexType) {
			throw new UnsupportedOperationException("Sodium draws go through MetalSodium.render on Metal");
		}

		@Override
		public void endTessellating() {
		}

		@Override
		public void close() {
		}
	}

	/** A region's vertex buffer + index buffer (Sodium's VAO). */
	record Tessellation(GlPrimitiveType primitiveType, TessellationBinding[] bindings) implements GlTessellation {
		@Override
		public void delete(CommandList commandList) {
		}

		@Override
		public void bind(CommandList commandList) {
		}

		@Override
		public void unbind(CommandList commandList) {
		}

		@Override
		public GlPrimitiveType getPrimitiveType() {
			return this.primitiveType;
		}

		GlBuffer vertexBuffer() {
			for (TessellationBinding b : this.bindings) if (b.target() == GlBufferTarget.ARRAY_BUFFER) return b.buffer();
			throw new IllegalStateException("no vertex buffer");
		}

		GlBuffer indexBuffer() {
			for (TessellationBinding b : this.bindings) if (b.target() == GlBufferTarget.ELEMENT_BUFFER) return b.buffer();
			throw new IllegalStateException("no index buffer");
		}
	}

	// ---- terrain rendering ----

	/** What DefaultChunkRenderer.render needs from the renderer itself (implemented by the mixin through invokers). */
	public interface RendererAccess {
		void metalforge$ensureSharedCapacity(CommandList commandList, int elementCount);

		GlTessellation metalforge$tessellation(CommandList commandList, RenderRegion.DeviceResources resources, boolean indexed);

		void metalforge$fill(MultiDrawBatch batch, RenderRegion region, net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage storage,
			ChunkRenderList renderList, CameraTransform camera, TerrainRenderPass pass, boolean faceCulling, boolean indexed);

		ShaderConstants metalforge$constants(ChunkShaderOptions options);

		ChunkVertexType metalforge$vertexType();
	}

	/** DefaultChunkRenderer.render, on Metal. */
	public static void render(RendererAccess renderer, ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists, TerrainRenderPass pass,
		CameraTransform camera, FogParameters fog, boolean indexedRenderingEnabled, GpuSampler terrainSampler, GpuBufferSlice uniformBuffer,
		GlTexelBuffer sectionTimeInfo) {
		MetalDevice d = device();
		MetalPipeline pipeline = pipeline(renderer, pass);
		if (!pipeline.isValid()) return;
		RenderTarget target = pass.getTarget();
		MetalRenderPass rp = (MetalRenderPass) d.encoder.createRenderPass(() -> "Sodium terrain", target.getColorTextureView(), OptionalInt.empty(),
			target.getDepthTextureView(), OptionalDouble.empty());
		try {
			rp.setRawPipeline(pipeline);
			rp.bindRawBuffer(pipeline.slotOf("u_Globals"), uniformBuffer);
			rp.bindRawTexture(pipeline.slotOf("u_BlockTex"), pass.getAtlas(), terrainSampler);
			rp.bindRawTexture(pipeline.slotOf("u_LightTex"), Minecraft.getInstance().gameRenderer.lightmap(),
				RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			GpuBuffer times = TEXELS.get(sectionTimeInfo);
			if (times != null) rp.bindTexel(pipeline.slotOf("u_SectionTimeInfo"), (MetalBuffer) times, 0, times.size(), 54 /* R32Sint */, 4);
			int loose = pipeline.slotOf(MetalPipeline.LOOSE_BLOCK);
			int offRegion = pipeline.looseOffsets.getOrDefault("u_RegionOffset", -1);
			int offTime = pipeline.looseOffsets.getOrDefault("u_CurrentTime", -1);
			int offId = pipeline.looseOffsets.getOrDefault("u_RegionID", -1);
			boolean faceCulling = SodiumClientMod.options().performance.useBlockFaceCulling;
			boolean indexed = pass.isTranslucent() && indexedRenderingEnabled;
			CommandList commands = COMMANDS;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				long looseData = stack.ncalloc(16, 1, Math.max(16, pipeline.looseSize));
				Iterator<ChunkRenderList> iterator = renderLists.iterator(pass.isTranslucent());
				while (iterator.hasNext()) {
					ChunkRenderList renderList = iterator.next();
					RenderRegion region = renderList.getRegion();
					var storage = region.getStorage(pass);
					if (storage == null) continue;
					var resources = region.getResources();
					if (resources == null) {
						region.clearCachedBatchFor(pass);
						continue;
					}
					MultiDrawBatch batch = region.getCachedBatch(pass);
					if (!batch.isFilled) renderer.metalforge$fill(batch, region, storage, renderList, camera, pass, faceCulling, indexed);
					if (batch.isEmpty()) continue;
					if (!indexed) renderer.metalforge$ensureSharedCapacity(commands, UInt32.downcast(batch.maxElementCount));
					Tessellation t = (Tessellation) renderer.metalforge$tessellation(commands, resources, indexed);
					// the region's loose uniforms (DefaultShaderInterface.setRegionData)
					if (offRegion >= 0) {
						MemoryUtil.memPutFloat(looseData + offRegion, (region.getOriginX() - camera.intX) - camera.fracX);
						MemoryUtil.memPutFloat(looseData + offRegion + 4, (region.getOriginY() - camera.intY) - camera.fracY);
						MemoryUtil.memPutFloat(looseData + offRegion + 8, (region.getOriginZ() - camera.intZ) - camera.fracZ);
					}
					if (offTime >= 0) MemoryUtil.memPutInt(looseData + offTime, Math.toIntExact(System.currentTimeMillis() - region.getCreationTime()));
					if (offId >= 0) MemoryUtil.memPutInt(looseData + offId, region.getId());
					rp.bindRawBytes(loose, looseData, pipeline.looseSize);
					rp.setRawVertexBuffer(buffer(t.vertexBuffer()));
					rp.setRawIndexBuffer(buffer(t.indexBuffer()), true);
					// one native call per region: mcopt's loop over Sodium's batch arrays (byte offsets, counts, base vertices)
					rp.drawRawMultiIndexed(batch.pElementPointer, batch.pElementCount, batch.pBaseVertex, batch.size);
				}
			}
		} finally {
			rp.close();
		}
	}

	private static MetalPipeline pipeline(RendererAccess renderer, TerrainRenderPass pass) {
		String key = pass.toString();
		MetalPipeline p = PIPELINES.get(key);
		if (p != null) return p;
		ChunkShaderOptions options = new ChunkShaderOptions(ChunkFogMode.SMOOTH, pass, renderer.metalforge$vertexType());
		ShaderConstants constants = renderer.metalforge$constants(options);
		String vsrc = ShaderParser.parseShader(ShaderLoader.getShaderSource(Identifier.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque.vsh")), constants).src();
		String fsrc = ShaderParser.parseShader(ShaderLoader.getShaderSource(Identifier.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque.fsh")), constants).src();
		// CompactChunkVertex: a_Position uvec2 @0, a_Color vec4 (unorm8x4) @8, a_TexCoord uvec2 (u16x2) @12, a_LightAndData uvec4 (u8x4) @16
		if (renderer.metalforge$vertexType().getVertexFormat().getStride() != 20) throw new IllegalStateException("unexpected Sodium vertex format");
		MetalPipeline.VertexLayout layout = new MetalPipeline.VertexLayout(List.of("a_Position", "a_Color", "a_TexCoord", "a_LightAndData"), 20,
			new int[] {0, 8, 12, 16}, new int[] {37 /* UInt2 */, 9 /* UChar4Normalized */, 13 /* UShort2 */, 3 /* UChar4 */});
		p = MetalPipeline.compileCustom(device().ctx, pass.getPipeline(), "sodium:" + key, vsrc, fsrc, layout, Map.of("u_SectionTimeInfo", new int[] {54, 4}));
		if (!p.isValid()) LOGGER.error("Metal: Sodium terrain pipeline {} failed to compile", key);
		PIPELINES.put(key, p);
		return p;
	}

	/** Shader reloads: drop compiled terrain pipelines. */
	public static void clearPipelines() {
		PIPELINES.values().forEach(p -> {
			if (p.isValid()) device().encoder.afterGpuFinishes(p::close);
		});
		PIPELINES.clear();
	}
}
