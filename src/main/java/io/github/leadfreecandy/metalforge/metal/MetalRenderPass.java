// Ported from mcopt (https://github.com/noahdunnagan/mcopt), Apache-2.0, Copyright 2026 Noah Dunnagan: lazy uniform binding
// (bind at the draw, only slots whose content changed). Re-shaped for 26.1.2's name-keyed uniforms and samplers.
package io.github.leadfreecandy.metalforge.metal;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

final class MetalRenderPass implements RenderPassBackend {
	/** METALFORGE_METAL_DEBUG=1: every 5 s, draws per pipeline (diagnostics). */
	static final boolean DEBUG = System.getenv("METALFORGE_METAL_DEBUG") != null;
	static final java.util.Map<String, long[]> DRAWS = new java.util.TreeMap<>();
	private static long lastDump = System.nanoTime();

	private void count(int draws, long indices) {
		if (!DEBUG) return;
		long[] c = DRAWS.computeIfAbsent(this.pipeline.name, k -> new long[2]);
		c[0] += draws;
		c[1] += indices;
		long now = System.nanoTime();
		if (now - lastDump > 5_000_000_000L) {
			lastDump = now;
			StringBuilder sb = new StringBuilder("metalforge draws (5 s):");
			DRAWS.forEach((k, v) -> sb.append("\n  ").append(k).append(": ").append(v[0]).append(" draws, ").append(v[1]).append(" indices"));
			System.out.println(sb);
			DRAWS.clear();
		}
	}
	private final MetalEncoder encoder;
	private final long enc;
	private final int width, height;
	private final int colorFormat, depthFormat;
	private boolean closed;
	private MetalPipeline pipeline = MetalPipeline.INVALID;
	private final Map<String, GpuBufferSlice> uniforms = new HashMap<>();
	private final Map<String, TextureBinding> samplers = new HashMap<>();
	/** What each MSL slot holds in the encoder now (buffer slices, texture bindings, texel views), so unchanged slots aren't re-sent. */
	private final Object[] bound = new Object[MetalConst.MAX_SLOTS];
	/** Texel buffers need an MTLTexture view per buffer slice; made once per pass. */
	private final Map<GpuBufferSlice, Long> texelViews = new HashMap<>();
	private @Nullable GpuBuffer vertexBuffer;
	private @Nullable GpuBuffer indexBuffer;
	private VertexFormat.IndexType indexType = VertexFormat.IndexType.INT;
	private long boundVertex, boundIndex;
	/** A uniform, sampler or pipeline changed since the last draw: the slot loop must run (mcopt binds lazily the same way). */
	private boolean dirty = true;
	private int renameEpoch = MetalBuffer.renames;
	private int boundIndexInt = -1;

	private record TextureBinding(MetalTexture.View view, MetalSampler sampler) {
	}

	MetalRenderPass(MetalEncoder encoder, MetalTexture.View color, MetalTexture.@Nullable View depth, int width, int height) {
		this.encoder = encoder;
		this.enc = encoder.enc;
		this.width = width;
		this.height = height;
		this.colorFormat = MetalConst.pixelFormat(color.texture().getFormat());
		this.depthFormat = depth == null ? 0 : MetalConst.pixelFormat(depth.texture().getFormat());
		Native.scissor(this.enc, 0, 0, width, height);
		Native.viewport(this.enc, 0, 0, width, height);
	}

	@Override
	public void pushDebugGroup(Supplier<String> label) {
	}

	@Override
	public void popDebugGroup() {
	}

	@Override
	public void setPipeline(RenderPipeline renderPipeline) {
		RenderPipeline info = RenderSystem.applyPipelineModifiers(renderPipeline);
		MetalPipeline p = this.encoder.device.pipeline(info);
		if (p == this.pipeline) return;
		this.pipeline = p;
		if (!p.isValid()) return;
		// Metal requires the pipeline's depth format to match the pass's attachment, whether or not the pipeline tests depth.
		long pso = p.state(this.colorFormat, this.depthFormat);
		Native.pipeline(this.enc, pso, this.depthFormat != 0 ? p.depthState : 0, p.cull ? 1 : 0, p.wireframe ? 1 : 0, p.depthBiasConstant, p.depthBiasSlope,
			p.primitive);
		// Slots mean different names in different pipelines.
		Arrays.fill(this.bound, null);
		this.dirty = true;
	}

	@Override
	public void bindTexture(String name, @Nullable GpuTextureView textureView, @Nullable GpuSampler sampler) {
		if (sampler == null || textureView == null) this.samplers.remove(name);
		else this.samplers.put(name, new TextureBinding((MetalTexture.View) textureView, (MetalSampler) sampler));
		this.dirty = true;
	}

	@Override
	public void setUniform(String name, GpuBuffer value) {
		this.uniforms.put(name, value.slice());
		this.dirty = true;
	}

	@Override
	public void setUniform(String name, GpuBufferSlice value) {
		this.uniforms.put(name, value);
		this.dirty = true;
	}

	@Override
	public void setViewport(int x, int y, int width, int height) {
		Native.viewport(this.enc, x, y, width, height);
	}

	@Override
	public void enableScissor(int x, int y, int width, int height) {
		// Metal rejects scissor rects outside the attachment; GL clips them.
		int x0 = Math.max(0, Math.min(x, this.width)), y0 = Math.max(0, Math.min(y, this.height));
		int x1 = Math.max(x0, Math.min(x + width, this.width)), y1 = Math.max(y0, Math.min(y + height, this.height));
		Native.scissor(this.enc, x0, y0, x1 - x0, y1 - y0);
	}

	@Override
	public void disableScissor() {
		Native.scissor(this.enc, 0, 0, this.width, this.height);
	}

	@Override
	public void setVertexBuffer(int slot, GpuBuffer vertexBuffer) {
		if (slot != 0) throw new IllegalArgumentException("Vertex buffer slot is out of range: " + slot);
		this.vertexBuffer = vertexBuffer;
	}

	@Override
	public void setIndexBuffer(@Nullable GpuBuffer indexBuffer, VertexFormat.IndexType indexType) {
		this.indexBuffer = indexBuffer;
		this.indexType = indexType;
	}

	/** Binds everything the current pipeline reads that changed since the last draw; false if the draw must be skipped. */
	private boolean setup() {
		MetalPipeline p = this.pipeline;
		if (!p.isValid()) return false;
		if (this.renameEpoch != MetalBuffer.renames) {
			this.renameEpoch = MetalBuffer.renames;
			this.dirty = true;
		}
		if (this.dirty) this.bindSlots(p);
		if (this.vertexBuffer != null) {
			MetalBuffer vb = this.encoder.use(this.vertexBuffer);
			if (vb.handle != this.boundVertex) {
				this.boundVertex = vb.handle;
				Native.vertexBuffer(this.enc, MetalConst.VERTEX_BUFFER_BASE, vb.handle, 0);
			}
		}
		return true;
	}

	private void bindSlots(MetalPipeline p) {
		this.dirty = false;
		for (int s = 0; s < p.slotCount; s++) {
			String name = p.slotName[s];
			switch (p.slotKind[s]) {
				case 0 -> {
					GpuBufferSlice slice = this.uniforms.get(name);
					if (slice == null) continue;
					this.bindBuffer(s, slice);
				}
				case 1 -> {
					TextureBinding t = this.samplers.get(name);
					if (t == null || t.equals(this.bound[s])) continue;
					this.bound[s] = t;
					Native.texture(this.enc, s, t.view.handle, t.sampler.handle);
				}
				default -> {
					GpuBufferSlice slice = this.uniforms.get(name);
					if (slice == null) continue;
					this.bindTexel(s, (MetalBuffer) slice.buffer(), slice.offset(), slice.length(), p.slotTexelFormat[s], p.slotTexelSize[s]);
				}
			}
		}
	}

	// ---- raw API for renderers that drive the pass themselves (Sodium on Metal) ----

	/** Sets a pipeline compiled outside the game's RenderPipeline cache (MetalPipeline.compileCustom). */
	void setRawPipeline(MetalPipeline p) {
		if (p == this.pipeline) return;
		this.pipeline = p;
		if (!p.isValid()) return;
		Native.pipeline(this.enc, p.state(this.colorFormat, this.depthFormat), this.depthFormat != 0 ? p.depthState : 0, p.cull ? 1 : 0, p.wireframe ? 1 : 0,
			p.depthBiasConstant, p.depthBiasSlope, p.primitive);
		Arrays.fill(this.bound, null);
	}

	void bindRawBuffer(int slot, GpuBufferSlice slice) {
		if (slot >= 0) this.bindBuffer(slot, slice);
	}

	void bindRawTexture(int slot, GpuTextureView view, GpuSampler sampler) {
		if (slot < 0) return;
		TextureBinding t = new TextureBinding((MetalTexture.View) view, (MetalSampler) sampler);
		if (t.equals(this.bound[slot])) return;
		this.bound[slot] = t;
		Native.texture(this.enc, slot, t.view.handle, t.sampler.handle);
	}

	void bindTexel(int slot, MetalBuffer buffer, long offset, long length, int format, int texelSize) {
		if (slot < 0) return;
		this.encoder.use(buffer);
		GpuBufferSlice key = new GpuBufferSlice(buffer, offset, length);
		long view = this.texelViews.computeIfAbsent(key, sl -> {
			long h = Native.textureBuffer(this.encoder.ctx, buffer.handle, format, offset, length, texelSize);
			this.encoder.releaseLater(h);
			return h;
		});
		if (Long.valueOf(view).equals(this.bound[slot])) return;
		this.bound[slot] = view;
		Native.texture(this.enc, slot, view, 0);
	}

	/** Small per-draw uniform data (setVertexBytes/setFragmentBytes); the slot is left unknown to the lazy binder. */
	void bindRawBytes(int slot, long address, int length) {
		if (slot < 0) return;
		this.bound[slot] = null;
		Native.bytes(this.enc, slot, address, length);
	}

	void setRawVertexBuffer(MetalBuffer vb) {
		this.encoder.use(vb);
		if (vb.handle != this.boundVertex) {
			this.boundVertex = vb.handle;
			Native.vertexBuffer(this.enc, MetalConst.VERTEX_BUFFER_BASE, vb.handle, 0);
		}
	}

	void setRawIndexBuffer(MetalBuffer ib, boolean ints) {
		this.encoder.use(ib);
		int isInt = ints ? 1 : 0;
		if (ib.handle != this.boundIndex || isInt != this.boundIndexInt) {
			this.boundIndex = ib.handle;
			this.boundIndexInt = isInt;
			Native.index(this.enc, ib.handle, isInt);
		}
	}

	void drawRawMultiIndexed(long offsets, long counts, long baseVertices, int drawCount) {
		Native.multiDrawIndexedSeparate(this.enc, offsets, counts, baseVertices, drawCount);
	}

	void drawRawIndexed(int indexCount, int firstIndex, int baseVertex) {
		Native.drawIndexed(this.enc, indexCount, 1, firstIndex, baseVertex, 0);
	}

	private void bindBuffer(int slot, GpuBufferSlice slice) {
		Object b = this.bound[slot];
		MetalBuffer buffer = this.encoder.use(slice.buffer());
		if (b instanceof BoundBuffer bb && bb.handle == buffer.handle && bb.offset == slice.offset()) return;
		this.bound[slot] = new BoundBuffer(buffer.handle, slice.offset());
		Native.buffer(this.enc, slot, buffer.handle, slice.offset());
	}

	private record BoundBuffer(long handle, long offset) {
	}

	private void bindIndex() {
		if (this.indexBuffer == null) return;
		MetalBuffer ib = this.encoder.use(this.indexBuffer);
		int isInt = this.indexType == VertexFormat.IndexType.INT ? 1 : 0;
		if (ib.handle != this.boundIndex || isInt != this.boundIndexInt) {
			this.boundIndex = ib.handle;
			this.boundIndexInt = isInt;
			Native.index(this.enc, ib.handle, isInt);
		}
	}

	@Override
	public void drawIndexed(int baseVertex, int firstIndex, int indexCount, int instanceCount) {
		if (!this.setup()) return;
		this.bindIndex();
		this.count(1, indexCount);
		Native.drawIndexed(this.enc, indexCount, instanceCount, firstIndex, baseVertex, 0);
	}

	@Override
	public <T> void drawMultipleIndexed(Collection<RenderPass.Draw<T>> draws, @Nullable GpuBuffer defaultIndexBuffer,
		VertexFormat.@Nullable IndexType defaultIndexType, Collection<String> dynamicUniforms, T uniformArgument) {
		if (!this.setup()) return;
		MetalPipeline p = this.pipeline;
		VertexFormat.IndexType defaultType = defaultIndexType == null ? VertexFormat.IndexType.SHORT : defaultIndexType;
		for (RenderPass.Draw<T> draw : draws) {
			this.setIndexBuffer(draw.indexBuffer() == null ? defaultIndexBuffer : draw.indexBuffer(), draw.indexType() == null ? defaultType : draw.indexType());
			this.vertexBuffer = draw.vertexBuffer();
			MetalBuffer vb = this.encoder.use(draw.vertexBuffer());
			if (vb.handle != this.boundVertex) {
				this.boundVertex = vb.handle;
				Native.vertexBuffer(this.enc, MetalConst.VERTEX_BUFFER_BASE, vb.handle, 0);
			}
			BiConsumer<T, RenderPass.UniformUploader> uploader = draw.uniformUploaderConsumer();
			if (uploader != null) {
				uploader.accept(uniformArgument, (name, slice) -> {
					Integer s = p.slots.get(name);
					if (s != null) this.bindBuffer(s, slice);
				});
			}
			this.bindIndex();
			this.count(1, draw.indexCount());
			Native.drawIndexed(this.enc, draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0);
		}
	}

	@Override
	public void draw(int firstVertex, int vertexCount) {
		if (!this.setup()) return;
		this.count(1, vertexCount);
		Native.draw(this.enc, vertexCount, 1, firstVertex, 0);
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			this.encoder.finishRenderPass();
		}
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}
}
