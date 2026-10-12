// Ported from mcopt (https://github.com/noahdunnagan/mcopt), Apache-2.0, Copyright 2026 Noah Dunnagan; re-targeted to 26.1.2.
package io.github.leadfreecandy.metalforge.metal;

import com.mojang.blaze3d.buffers.GpuBuffer;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import org.lwjgl.system.MemoryUtil;

/**
 * A shared-storage MTLBuffer. On Apple silicon the CPU mapping *is* the GPU memory, so map() is a pointer offset and uploads
 * never need a staging copy when the GPU isn't using the buffer. Mapping is unsynchronized (the game fences its ring buffers).
 */
class MetalBuffer extends GpuBuffer {
	/** The memory, which rename() can swap. */
	long handle, address;
	/** Submit index of the last command that touched this buffer on the GPU; see MetalEncoder.idle. */
	long lastUse = -1;
	private final MetalEncoder encoder;
	private boolean closed;
	int mapped;
	/** Memory rename() moved off and the GPU is done with, for the next rename. */
	private final ArrayDeque<Long> spares = new ArrayDeque<>();

	MetalBuffer(MetalEncoder encoder, long handle, @GpuBuffer.Usage int usage, long size) {
		this(encoder, handle, usage, size, Native.bufferContents(handle));
	}

	MetalBuffer(MetalEncoder encoder, long handle, @GpuBuffer.Usage int usage, long size, long address) {
		super(usage, size);
		this.encoder = encoder;
		this.handle = handle;
		this.address = address;
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			this.encoder.releaseLater(this.handle);
			for (long spare : this.spares) this.encoder.releaseLater(spare);
			this.spares.clear();
		}
	}

	/**
	 * Moves this buffer onto other memory, for a write replacing all of it while the GPU may still read the old contents:
	 * commands encoded before keep reading those, and the old memory is reused once this submit is done with it. False
	 * while someone has it mapped (their view would keep pointing at the old memory).
	 */
	/** Bumped on every rename: bound slots may point at memory a buffer just left (MetalRenderPass rebinds). */
	static int renames;

	boolean rename() {
		if (this.mapped > 0) return false;
		renames++;
		long old = this.handle;
		Long spare = this.spares.poll();
		this.handle = spare != null ? spare : Native.bufferNew(this.encoder.ctx, this.size());
		this.address = Native.bufferContents(this.handle);
		this.lastUse = -1;
		this.encoder.afterGpuFinishes(() -> {
			if (this.closed) Native.release(old);
			else this.spares.add(old);
		});
		return true;
	}

	GpuBuffer.MappedView map(long offset, long length) {
		if (this.closed) throw new IllegalStateException("Buffer already closed");
		if (offset < 0 || length < 0 || offset + length > this.size()) throw new IllegalArgumentException("Mapping out of range");
		this.mapped++;
		ByteBuffer data = MemoryUtil.memByteBuffer(this.address + offset, Math.toIntExact(length));
		return new GpuBuffer.MappedView() {
			private boolean done;

			@Override
			public ByteBuffer data() {
				return data;
			}

			@Override
			public void close() {
				if (!this.done) {
					this.done = true;
					MetalBuffer.this.mapped--;
				}
			}
		};
	}
}
