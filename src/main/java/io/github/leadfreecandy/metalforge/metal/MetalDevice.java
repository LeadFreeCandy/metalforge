// Ported from mcopt (https://github.com/noahdunnagan/mcopt), Apache-2.0, Copyright 2026 Noah Dunnagan; re-targeted to 26.1.2's
// GpuDeviceBackend.
package io.github.leadfreecandy.metalforge.metal;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

final class MetalDevice implements GpuDeviceBackend {
	private static final Logger LOGGER = LogUtils.getLogger();
	/** The Metal device the game runs on, or null when it runs on another backend. */
	static volatile @Nullable MetalDevice active;

	/** True while the Metal backend paces frames itself (MetalPacer): vanilla's frame limiter must not wait as well. */
	static boolean smoothPacing() {
		MetalDevice d = active;
		return d != null && d.surface.smooth();
	}
	final long ctx;
	final MetalEncoder encoder;
	final MetalSurface surface;
	private final String renderer;
	private final ShaderSource defaultShaderSource;
	private final Map<RenderPipeline, MetalPipeline> pipelines = new IdentityHashMap<>();

	MetalDevice(long window, ShaderSource defaultShaderSource) {
		this.defaultShaderSource = defaultShaderSource;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long name = stack.nmalloc(1, 256), err = stack.nmalloc(1, 1024);
			this.ctx = Native.create(name, 256, err, 1024);
			if (this.ctx == 0) throw new IllegalStateException("Metal unavailable: " + MemoryUtil.memUTF8(err));
			this.renderer = MemoryUtil.memUTF8(name);
		}
		this.encoder = new MetalEncoder(this, this.ctx);
		this.surface = new MetalSurface(this.ctx, window);
		LOGGER.info("MetalForge Metal backend (ported from mcopt) on {}", this.renderer);
	}

	MetalPipeline pipeline(RenderPipeline pipeline) {
		MetalPipeline p = this.pipelines.get(pipeline);
		if (p == null) {
			p = MetalPipeline.compile(this.ctx, pipeline, this.defaultShaderSource);
			if (p == null) return MetalPipeline.INVALID;
			this.pipelines.put(pipeline, p);
		}
		return p;
	}

	@Override
	public CommandEncoderBackend createCommandEncoder() {
		return this.encoder;
	}

	@Override
	public GpuSampler createSampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int maxAnisotropy, OptionalDouble maxLod) {
		return MetalSampler.create(this.ctx, u, v, min, mag, maxAnisotropy, maxLod);
	}

	@Override
	public GpuTexture createTexture(@Nullable Supplier<String> label, @GpuTexture.Usage int usage, TextureFormat format, int width, int height, int layers, int mips) {
		return this.createTexture(label == null ? null : label.get(), usage, format, width, height, layers, mips);
	}

	@Override
	public GpuTexture createTexture(@Nullable String label, @GpuTexture.Usage int usage, TextureFormat format, int width, int height, int layers, int mips) {
		// MTLTextureUsage: ShaderRead 1, RenderTarget 4. Copies need no usage bit in Metal.
		int mtlUsage = ((usage & GpuTexture.USAGE_TEXTURE_BINDING) != 0 ? 1 : 0) | ((usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0 ? 4 : 0);
		boolean cube = (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;
		long handle = Native.textureNew(this.ctx, MetalConst.pixelFormat(format), width, height, layers, mips, Math.max(1, mtlUsage), cube ? 1 : 0);
		if (handle == 0) throw new IllegalStateException("Couldn't create " + width + "x" + height + " " + format + " texture " + label);
		return new MetalTexture(this.encoder, handle, usage, label == null ? "" : label, format, width, height, layers, mips);
	}

	@Override
	public GpuTextureView createTextureView(GpuTexture texture) {
		return this.createTextureView(texture, 0, texture.getMipLevels());
	}

	@Override
	public GpuTextureView createTextureView(GpuTexture texture, int baseMip, int mips) {
		return new MetalTexture.View(this.encoder, (MetalTexture) texture, baseMip, mips);
	}

	@Override
	public GpuBuffer createBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, long size) {
		return new MetalBuffer(this.encoder, Native.bufferNew(this.ctx, Math.max(16, size)), usage, size);
	}

	@Override
	public GpuBuffer createBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, ByteBuffer data) {
		// A brand-new buffer isn't referenced by any command yet, so the data can go straight into its memory.
		MetalBuffer buffer = new MetalBuffer(this.encoder, Native.bufferNew(this.ctx, Math.max(16, data.remaining())), usage, data.remaining());
		MemoryUtil.memCopy(MemoryUtil.memAddress(data), buffer.address, data.remaining());
		return buffer;
	}

	@Override
	public String getImplementationInformation() {
		return "Metal (MetalForge, ported from mcopt) on " + this.renderer;
	}

	@Override
	public List<String> getLastDebugMessages() {
		return List.of();
	}

	@Override
	public boolean isDebuggingEnabled() {
		return false;
	}

	@Override
	public String getVendor() {
		return "Apple";
	}

	@Override
	public String getBackendName() {
		return "Metal";
	}

	@Override
	public String getVersion() {
		return "Metal 3 (MetalForge/mcopt)";
	}

	@Override
	public String getRenderer() {
		return this.renderer;
	}

	@Override
	public int getMaxTextureSize() {
		return 16384;
	}

	@Override
	public int getUniformOffsetAlignment() {
		return 256;
	}

	@Override
	public CompiledRenderPipeline precompilePipeline(RenderPipeline pipeline, @Nullable ShaderSource shaderSource) {
		MetalPipeline p = this.pipelines.get(pipeline);
		if (p == null) {
			p = MetalPipeline.compile(this.ctx, pipeline, shaderSource == null ? this.defaultShaderSource : shaderSource);
			if (p == null) return MetalPipeline.INVALID;
			this.pipelines.put(pipeline, p);
		}
		return p;
	}

	@Override
	public void clearPipelineCache() {
		for (MetalPipeline p : this.pipelines.values()) {
			if (p.isValid()) this.encoder.afterGpuFinishes(p::close);
		}
		this.pipelines.clear();
	}

	@Override
	public List<String> getEnabledExtensions() {
		return List.of();
	}

	@Override
	public int getMaxSupportedAnisotropy() {
		return 16;
	}

	@Override
	public void close() {
		this.encoder.waitIdle();
	}

	@Override
	public void setVsync(boolean enabled) {
		this.surface.setVsync(enabled);
	}

	@Override
	public void presentFrame() {
		this.encoder.submit(true);
		if (this.surface.smooth()) this.surface.pacer.endFrame();
		long t = System.nanoTime();
		if (t - this.shownLoggedAt > 5_000_000_000L) {
			// on-screen spacing of displayed frames (native shownWatch): 1 refresh apart = smooth, 2 = a refresh repeated, 3+ = hitch
			if (this.shownLoggedAt != 0) org.slf4j.LoggerFactory.getLogger("MetalForge").info("[shown] 1x={} 2x={} 3x+={} untimed={} smooth={}",
				Native.shown(1), Native.shown(2), Native.shown(3), Native.shown(0), this.surface.smooth());
			else for (int k = 0; k < 4; k++) Native.shown(k);
			this.shownLoggedAt = t;
		}
	}

	private long shownLoggedAt;

	@Override
	public boolean isZZeroToOne() {
		return true;
	}
}
