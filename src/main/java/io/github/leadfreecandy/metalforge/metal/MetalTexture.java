// Ported from mcopt (https://github.com/noahdunnagan/mcopt), Apache-2.0, Copyright 2026 Noah Dunnagan; re-targeted to 26.1.2.
package io.github.leadfreecandy.metalforge.metal;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import org.jspecify.annotations.Nullable;

class MetalTexture extends GpuTexture {
	final long handle;
	private final MetalEncoder encoder;
	private boolean closed;

	/**
	 * A clear that hasn't touched memory yet. Tile GPUs pay a full framebuffer write for a standalone clear and another
	 * read when the next pass loads it; folding the clear into the next pass's load action costs nothing.
	 * Color: {r, g, b, a}; null when none is pending.
	 */
	float @Nullable [] pendingColorClear;
	double pendingDepthClear = Double.NaN;

	MetalTexture(MetalEncoder encoder, long handle, @GpuTexture.Usage int usage, String label, TextureFormat format, int width, int height, int layers, int mips) {
		super(usage, label, format, width, height, layers, mips);
		this.encoder = encoder;
		this.handle = handle;
	}

	boolean hasPendingClear() {
		return this.pendingColorClear != null || !Double.isNaN(this.pendingDepthClear);
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			this.encoder.dropPendingClear(this);
			this.encoder.releaseLater(this.handle);
		}
	}

	static final class View extends GpuTextureView {
		final long handle;
		private final boolean ownsHandle;
		private final MetalEncoder encoder;
		private boolean closed;

		View(MetalEncoder encoder, MetalTexture texture, int baseMip, int mips) {
			super(texture, baseMip, mips);
			this.encoder = encoder;
			// A view of the whole texture is the texture itself; only sub-ranges need a real MTLTexture view.
			this.ownsHandle = baseMip != 0 || mips != texture.getMipLevels();
			this.handle = this.ownsHandle ? Native.textureView(texture.handle, baseMip, mips) : texture.handle;
		}

		MetalTexture metalTexture() {
			return (MetalTexture) this.texture();
		}

		@Override
		public boolean isClosed() {
			return this.closed || this.texture().isClosed();
		}

		@Override
		public void close() {
			if (!this.closed) {
				this.closed = true;
				if (this.ownsHandle) this.encoder.releaseLater(this.handle);
			}
		}
	}

	static MetalTexture of(GpuTextureView view) {
		return (MetalTexture) view.texture();
	}
}
