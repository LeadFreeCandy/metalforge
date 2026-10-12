// Ported from mcopt (https://github.com/noahdunnagan/mcopt), Apache-2.0, Copyright 2026 Noah Dunnagan: frame / submit model,
// deferred clears folded into load actions, merged passes, CPU uploads into idle shared memory, fences by submit index.
// Re-shaped for 26.1.2's CommandEncoderBackend (single color attachment, int ARGB clears, name-based uniforms, present via
// presentTexture + GpuDeviceBackend.presentFrame, no TransientMemory).
package io.github.leadfreecandy.metalforge.metal;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuQuery;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.util.ARGB;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** One MTLCommandBuffer per frame (submit). Metal tracks hazards itself, so copies, clears and passes are simply recorded in order. */
final class MetalEncoder implements CommandEncoderBackend {
	/** Submitting frame N waits for frame N-2 (as the Vulkan backend does). */
	private static final int MAX_IN_FLIGHT = Integer.getInteger("metalforge.metal.inFlight", 2);
	/** Above this an upload is cheaper as an async GPU blit than as a memcpy on the render thread. */
	private static final long CPU_COPY_MAX = 256 << 10;
	private static final long RENAME_COPY_MAX = 8L << 20;
	private static final boolean STATS = Boolean.getBoolean("metalforge.metal.stats");
	/** METALFORGE_METAL_TRACE=N: log every pass / clear / copy of submit N (where encoders open). */
	private static final long TRACE = Long.parseLong(System.getenv().getOrDefault("METALFORGE_METAL_TRACE", "-1"));

	private void trace(String what) {
		if (this.submitIndex == TRACE) System.out.println("metalforge trace #" + this.submitIndex + ": " + what);
	}

	final long ctx;
	final long enc;
	final MetalDevice device;
	private final Staging staging;
	private final ArrayDeque<Frame> inFlight = new ArrayDeque<>();
	private List<Runnable> afterThisFrame = new ArrayList<>();
	private final Set<MetalTexture> pendingClears = new HashSet<>();
	long submitIndex;
	private long completedIndex = -1;
	private @Nullable MetalRenderPass currentPass;
	private long waitNanos, statStart;
	private int statFrames, statPresents;

	private record Frame(long index, long cmd, List<Runnable> after) {
	}

	MetalEncoder(MetalDevice device, long ctx) {
		this.device = device;
		this.ctx = ctx;
		this.enc = Native.encNew(ctx);
		this.staging = new Staging();
	}

	/** Marks a buffer as referenced by the submit being recorded. Every GPU reference to a buffer must go through here. */
	MetalBuffer use(GpuBuffer buffer) {
		MetalBuffer b = (MetalBuffer) buffer;
		b.lastUse = this.submitIndex;
		return b;
	}

	/** True when no recorded or in-flight GPU work touches b, so the CPU may read and write it right now. */
	private boolean idle(MetalBuffer b) {
		return b.lastUse <= this.completedIndex;
	}

	void releaseLater(long handle) {
		if (handle != 0) this.afterThisFrame.add(() -> Native.release(handle));
	}

	void afterGpuFinishes(Runnable r) {
		this.afterThisFrame.add(r);
	}

	/**
	 * -Dmetalforge.metal.depthDiscard (default on): the main target's depth is not written back at the end of a frame. The game
	 * clears it at the start of every frame (and before the GUI), and nothing reads it after the frame (screenshots read color),
	 * so the store (21 MB at 2940x1782) is dead. mcopt's frameDepthDiscard.
	 */
	private static final boolean DEPTH_DISCARD = Boolean.parseBoolean(System.getProperty("metalforge.metal.depthDiscard", "true"));

	/** Ends the frame's recording: commits the command buffer and keeps at most MAX_IN_FLIGHT frames on the GPU. */
	void submit() {
		this.submit(false);
	}

	/** frameEnd: the game finished its frame (presentFrame); only then is the main depth dead. */
	void submit(boolean frameEnd) {
		if (this.currentPass != null) throw new IllegalStateException("Cannot submit inside a render pass");
		MetalStats.submits++;
		this.staging.endSubmit();
		long cmd = Native.encCommit(this.enc);
		this.inFlight.add(new Frame(this.submitIndex++, cmd, this.afterThisFrame));
		this.afterThisFrame = new ArrayList<>();
		while (this.inFlight.size() > MAX_IN_FLIGHT) this.retire(this.inFlight.poll());
		if (STATS) this.stats();
	}

	private void stats() {
		long now = System.nanoTime();
		this.statFrames++;
		if (this.statStart == 0) this.statStart = now;
		if (now - this.statStart < 1_000_000_000L) return;
		double frameMs = (now - this.statStart) / 1e6 / this.statFrames;
		System.out.printf("metalforge stats: %d fps, %.3f ms/frame, %.3f ms of it waiting on the GPU, %d presents%n", this.statFrames, frameMs,
			this.waitNanos / 1e6 / this.statFrames, this.statPresents);
		this.statStart = now;
		this.statFrames = 0;
		this.statPresents = 0;
		this.waitNanos = 0;
	}

	private void retire(Frame frame) {
		long t0 = System.nanoTime();
		Native.cmdWait(frame.cmd);
		this.waitNanos += System.nanoTime() - t0;
		MetalStats.gpuMicros += Native.cmdGpuMicros(frame.cmd);
		MetalStats.gpuFrames++;
		frame.after.forEach(Runnable::run);
		Native.release(frame.cmd);
		this.completedIndex = frame.index;
	}

	void waitIdle() {
		if (this.currentPass == null) this.submit();
		while (!this.inFlight.isEmpty()) this.retire(this.inFlight.poll());
	}

	// ---- render passes ----

	@Override
	public RenderPassBackend createRenderPass(Supplier<String> label, GpuTextureView colorTexture, OptionalInt clearColor) {
		return this.createRenderPass(label, colorTexture, clearColor, null, OptionalDouble.empty());
	}

	@Override
	public RenderPassBackend createRenderPass(Supplier<String> label, GpuTextureView colorTexture, OptionalInt clearColor,
		@Nullable GpuTextureView depthTexture, OptionalDouble clearDepth) {
		if (this.currentPass != null) throw new IllegalStateException("Close the existing render pass before creating a new one!");
		MetalTexture.View color = (MetalTexture.View) colorTexture;
		MetalTexture.View depth = (MetalTexture.View) depthTexture;
		// Anything sampled inside this pass must have its deferred clear in memory before the pass starts.
		for (MetalTexture t : List.copyOf(this.pendingClears)) {
			if (t.isClosed()) this.pendingClears.remove(t);
			else if (t != color.metalTexture() && (depth == null || t != depth.metalTexture())) this.flushClear(t);
		}
		int width = color.getWidth(0), height = color.getHeight(0);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long colors = stack.nmalloc(8, 8);
			long clears = stack.ncalloc(4, 5, 4);
			MemoryUtil.memPutAddress(colors, color.handle);
			float[] c = clearColor.isPresent() ? argb(clearColor.getAsInt()) : color.baseMipLevel() == 0 ? color.metalTexture().pendingColorClear : null;
			if (color.baseMipLevel() == 0) color.metalTexture().pendingColorClear = null;
			if (c != null) putClear(clears, c);
			long depthHandle = 0;
			boolean doClearDepth = false;
			double depthValue = 0;
			if (depth != null) {
				MetalTexture t = depth.metalTexture();
				depthHandle = depth.handle;
				doClearDepth = clearDepth.isPresent() || !Double.isNaN(t.pendingDepthClear);
				depthValue = doClearDepth ? clearDepth.orElse(t.pendingDepthClear) : 0;
				t.pendingDepthClear = Double.NaN;
			}
			this.pendingClears.removeIf(t -> !t.hasPendingClear());
			int r = Native.renderBegin(this.enc, 1, colors, clears, depthHandle, doClearDepth ? 1 : 0, (float) depthValue, width, height);
			if (r == 0) MetalStats.encoders++;
			if (TRACE >= 0) this.trace((r == 0 ? "NEW encoder " : r == 1 ? "merged      " : "merged+dclr ") + label.get() + " -> " + color.texture().getLabel()
				+ "/" + color.baseMipLevel() + (depth != null ? " +depth " + depth.texture().getLabel() : "") + " " + width + "x" + height
				+ (c != null ? " clearColor" : "") + (doClearDepth ? " clearDepth" : ""));
		}
		this.currentPass = new MetalRenderPass(this, color, depth, width, height);
		return this.currentPass;
	}

	/** Called by MetalRenderPass.close. The render encoder stays open: a following pass on the same attachments continues it. */
	void finishRenderPass() {
		this.currentPass = null;
	}

	@Override
	public boolean isInRenderPass() {
		return this.currentPass != null;
	}

	private static float[] argb(int argb) {
		return new float[] {ARGB.redFloat(argb), ARGB.greenFloat(argb), ARGB.blueFloat(argb), ARGB.alphaFloat(argb)};
	}

	private static void putClear(long at, float[] c) {
		MemoryUtil.memPutFloat(at, 1);
		MemoryUtil.memPutFloat(at + 4, c[0]);
		MemoryUtil.memPutFloat(at + 8, c[1]);
		MemoryUtil.memPutFloat(at + 12, c[2]);
		MemoryUtil.memPutFloat(at + 16, c[3]);
	}

	// ---- clears: deferred into the next pass's load action ----

	void dropPendingClear(MetalTexture t) {
		t.pendingColorClear = null;
		t.pendingDepthClear = Double.NaN;
		this.pendingClears.remove(t);
	}

	/** Writes a deferred clear to memory with an empty pass: needed when the texture is read before it is drawn to. */
	void flushClear(MetalTexture t) {
		if (!t.hasPendingClear()) return;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			boolean depthFormat = t.getFormat().hasDepthAspect();
			long colors = stack.nmalloc(8, 8);
			long clears = stack.ncalloc(4, 5, 4);
			MemoryUtil.memPutAddress(colors, depthFormat ? 0 : t.handle);
			if (t.pendingColorClear != null) putClear(clears, t.pendingColorClear);
			boolean clearDepth = !Double.isNaN(t.pendingDepthClear);
			if (Native.renderBegin(this.enc, depthFormat ? 0 : 1, colors, clears, depthFormat ? t.handle : 0, clearDepth ? 1 : 0,
				clearDepth ? (float) t.pendingDepthClear : 0, t.getWidth(0), t.getHeight(0)) == 0) MetalStats.encoders++;
			if (TRACE >= 0) this.trace("flushClear  " + t.getLabel());
		}
		t.pendingColorClear = null;
		t.pendingDepthClear = Double.NaN;
		this.pendingClears.remove(t);
	}

	private void deferClear(GpuTexture texture, float @Nullable [] color, double depth) {
		MetalTexture t = (MetalTexture) texture;
		if (t.getMipLevels() != 1) {
			// Rare (mip chains): clear every level for real rather than tracking per-level state.
			for (int mip = 0; mip < t.getMipLevels(); mip++) this.clearRegion(t, color, null, depth, 0, 0, t.getWidth(mip), t.getHeight(mip), mip);
			return;
		}
		if (color != null) t.pendingColorClear = color;
		if (!Double.isNaN(depth)) t.pendingDepthClear = depth;
		this.pendingClears.add(t);
		Native.discard(this.enc, t.handle); // old contents are dead: if the open encoder renders to t, skip writing it back
	}

	@Override
	public void clearColorTexture(GpuTexture colorTexture, int clearColor) {
		this.deferClear(colorTexture, argb(clearColor), Double.NaN);
	}

	@Override
	public void clearColorAndDepthTextures(GpuTexture colorTexture, int clearColor, GpuTexture depthTexture, double clearDepth) {
		this.deferClear(colorTexture, argb(clearColor), Double.NaN);
		this.deferClear(depthTexture, null, clearDepth);
	}

	@Override
	public void clearColorAndDepthTextures(GpuTexture colorTexture, int clearColor, GpuTexture depthTexture, double clearDepth,
		int x, int y, int width, int height) {
		if (x == 0 && y == 0 && width == colorTexture.getWidth(0) && height == colorTexture.getHeight(0)) {
			this.clearColorAndDepthTextures(colorTexture, clearColor, depthTexture, clearDepth);
		} else {
			this.clearRegion((MetalTexture) colorTexture, argb(clearColor), (MetalTexture) depthTexture, clearDepth, x, y, width, height, 0);
		}
	}

	@Override
	public void clearDepthTexture(GpuTexture depthTexture, double clearDepth) {
		this.deferClear(depthTexture, null, clearDepth);
	}

	@Override
	public void clearStencilTexture(GpuTexture texture, int value) {
		// Stencil isn't plumbed through the native layer yet (vanilla's targets have none); a stencil user would need it.
	}

	private void clearRegion(@Nullable MetalTexture color, float @Nullable [] clearColor, @Nullable MetalTexture depth, double clearDepth,
		int x, int y, int width, int height, int mip) {
		if (color != null) this.flushClear(color);
		if (depth != null) this.flushClear(depth);
		float[] c = clearColor != null ? clearColor : new float[4];
		Native.clearRect(this.enc, color != null ? color.handle : 0, depth != null ? depth.handle : 0, c[0], c[1], c[2], c[3],
			Double.isNaN(clearDepth) ? -1 : (float) clearDepth, x, y, width, height, mip);
	}

	// ---- buffers ----

	@Override
	public void writeToBuffer(GpuBufferSlice destination, ByteBuffer data) {
		MetalBuffer dst = (MetalBuffer) destination.buffer();
		// A blit would end the open render encoder and wait for every command before it that reads dst. A buffer the GPU isn't
		// using can just be written (same memory); one it is using can move to fresh memory if the write replaces all of it.
		// A write to the whole buffer (slice() of it, as vanilla's immediate vertex/index uploads do, even with less data than the
		// buffer holds) replaces its contents: moving the buffer to fresh memory keeps the GPU's in-flight reads valid without a GPU
		// copy, which would end the open render encoder (a full-screen store + load per draw). Up to RENAME_COPY_MAX bytes.
		boolean whole = destination.offset() == 0 && destination.length() == dst.size();
		if (data.remaining() <= CPU_COPY_MAX && this.idle(dst) || whole && data.remaining() <= RENAME_COPY_MAX && (this.idle(dst) || dst.rename())) {
			MemoryUtil.memCopy(MemoryUtil.memAddress(data), dst.address + destination.offset(), data.remaining());
			return;
		}
		Staging.Slice s = this.staging.upload(data, 16);
		if (TRACE >= 0) this.trace("buffer blit " + data.remaining() + " bytes");
		Native.blitBuffer(this.enc, s.handle(), s.offset(), this.use(dst).handle, destination.offset(), data.remaining());
	}

	/** Writes size bytes at src into dst at dstOffset: a memcpy when the GPU isn't using dst, else a staged GPU copy in order. */
	void writeRaw(MetalBuffer dst, long dstOffset, long src, long size) {
		if (size <= CPU_COPY_MAX && this.idle(dst)) {
			MemoryUtil.memCopy(src, dst.address + dstOffset, size);
			return;
		}
		Staging.Slice s = this.staging.copy(src, size, 16);
		Native.blitBuffer(this.enc, s.handle(), s.offset(), this.use(dst).handle, dstOffset, size);
	}

	void copyRaw(MetalBuffer src, long srcOffset, MetalBuffer dst, long dstOffset, long size) {
		if (size <= CPU_COPY_MAX && src != dst && this.idle(src) && this.idle(dst)) {
			MemoryUtil.memCopy(src.address + srcOffset, dst.address + dstOffset, size);
			return;
		}
		Native.blitBuffer(this.enc, this.use(src).handle, srcOffset, this.use(dst).handle, dstOffset, size);
	}

	@Override
	public GpuBuffer.MappedView mapBuffer(GpuBufferSlice slice, boolean read, boolean write) {
		// Unsynchronized, like Vulkan: the game fences its rings and readbacks before mapping.
		return ((MetalBuffer) slice.buffer()).map(slice.offset(), slice.length());
	}

	@Override
	public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
		MetalBuffer src = (MetalBuffer) source.buffer(), dst = (MetalBuffer) target.buffer();
		if (source.length() <= CPU_COPY_MAX && src != dst && this.idle(src) && this.idle(dst)) {
			MemoryUtil.memCopy(src.address + source.offset(), dst.address + target.offset(), source.length());
			return;
		}
		Native.blitBuffer(this.enc, this.use(src).handle, source.offset(), this.use(dst).handle, target.offset(), source.length());
	}

	// ---- textures ----

	@Override
	public void writeToTexture(GpuTexture destination, NativeImage source, int mipLevel, int depthOrLayer, int destX, int destY, int width, int height,
		int sourceX, int sourceY) {
		MetalTexture t = (MetalTexture) destination;
		this.flushClear(t);
		if (TRACE >= 0) this.trace("upload image -> " + t.getLabel() + " mip " + mipLevel + " " + width + "x" + height);
		int pixel = source.format().components();
		int rowBytes = source.getWidth() * pixel;
		long src = source.getPointer() + ((long) sourceY * source.getWidth() + sourceX) * pixel;
		long length = (long) (height - 1) * rowBytes + (long) width * pixel;
		Staging.Slice s = this.staging.copy(src, length, 16);
		Native.blitBufferToTexture(this.enc, s.handle(), s.offset(), rowBytes, rowBytes * height, t.handle, depthOrLayer, mipLevel, destX, destY, width, height);
	}

	@Override
	public void writeToTexture(GpuTexture destination, ByteBuffer source, NativeImage.Format format, int mipLevel, int depthOrLayer, int destX, int destY,
		int width, int height) {
		MetalTexture t = (MetalTexture) destination;
		this.flushClear(t);
		int rowBytes = width * format.components();
		if (TRACE >= 0) this.trace("upload bytes -> " + t.getLabel() + " mip " + mipLevel + " " + width + "x" + height);
		Staging.Slice s = this.staging.copy(MemoryUtil.memAddress(source), (long) rowBytes * height, 16);
		Native.blitBufferToTexture(this.enc, s.handle(), s.offset(), rowBytes, rowBytes * height, t.handle, depthOrLayer, mipLevel, destX, destY, width, height);
	}

	@Override
	public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel) {
		this.copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
	}

	@Override
	public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel, int x, int y, int width, int height) {
		MetalTexture t = (MetalTexture) source;
		this.flushClear(t);
		Native.blitTextureToBuffer(this.enc, t.handle, mipLevel, x, y, width, height, this.use(destination).handle, offset, width * source.getFormat().pixelSize());
		RenderSystem.queueFencedTask(callback);
	}

	@Override
	public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mipLevel, int destX, int destY, int sourceX, int sourceY, int width, int height) {
		this.flushClear((MetalTexture) source);
		this.flushClear((MetalTexture) destination);
		if (TRACE >= 0) this.trace("copy texture " + source.getLabel() + " -> " + destination.getLabel());
		Native.blitTextureToTexture(this.enc, ((MetalTexture) source).handle, ((MetalTexture) destination).handle, mipLevel, destX, destY, sourceX, sourceY,
			width, height);
	}

	// ---- present ----

	@Override
	public void presentTexture(GpuTextureView texture) {
		MetalTexture.View view = (MetalTexture.View) texture;
		// The frame is drawn: its main depth is dead (cleared before it's next used). Must happen before mc_present ends the
		// encoder, which is when store actions are decided. No-op unless the open encoder has that depth attached.
		if (DEPTH_DISCARD) {
			var main = net.minecraft.client.Minecraft.getInstance().getMainRenderTarget();
			if (main != null && main.getDepthTexture() instanceof MetalTexture t) Native.discard(this.enc, t.handle);
		}
		this.flushClear(view.metalTexture());
		if (MetalSurface.PRESENT_QUEUE) {
			if (!this.device.surface.shouldPresent(view.getWidth(0), view.getHeight(0))) {
				MetalStats.skipped++;
				return;
			}
			if (this.device.surface.smooth()) Native.presentTarget(this.device.surface.pacer.target());
			if (Native.presentQueuedAcquire(this.enc, this.device.surface.layer, view.handle, -1) != 0) MetalStats.presents++;
			else MetalStats.skipped++;
			return;
		}
		long drawable = this.device.surface.acquire(view.getWidth(0), view.getHeight(0));
		if (drawable == 0) {
			MetalStats.skipped++;
			return; // paced out (a later frame makes this refresh) or no drawable (window hidden)
		}
		Native.present(this.enc, drawable, view.handle);
		MetalStats.presents++;
		this.afterGpuFinishes(() -> Native.release(drawable));
		this.statPresents++;
	}

	// ---- sync ----

	@Override
	public GpuFence createFence() {
		long index = this.submitIndex;
		return new GpuFence() {
			@Override
			public boolean awaitCompletion(long timeoutMs) {
				MetalEncoder e = MetalEncoder.this;
				if (e.completedIndex >= index) return true;
				if (index == e.submitIndex) {
					if (timeoutMs == 0) return false;
					// Waiting on work still being recorded: end the submit here (only possible outside a pass).
					if (e.currentPass != null) return false;
					e.submit();
				}
				while (e.completedIndex < index) {
					Frame oldest = e.inFlight.peek();
					if (oldest == null) return true;
					if (timeoutMs == 0 && !Native.cmdDone(oldest.cmd)) return false;
					e.retire(e.inFlight.poll());
				}
				return true;
			}

			@Override
			public void close() {
			}
		};
	}

	@Override
	public GpuQuery timerQueryBegin() {
		return EMPTY_QUERY;
	}

	@Override
	public void timerQueryEnd(GpuQuery query) {
	}

	private static final GpuQuery EMPTY_QUERY = new GpuQuery() {
		@Override
		public OptionalLong getValue() {
			return OptionalLong.empty();
		}

		@Override
		public void close() {
		}
	};

	/**
	 * Per-submit upload memory: shared MTLBuffer blocks, recycled once the GPU is done with the submit that used them. With
	 * unified memory an upload is a single memcpy; the GPU copies from here into private textures or busy buffers.
	 */
	private final class Staging {
		private static final long BLOCK = 4L << 20;

		record Slice(long handle, long offset) {
		}

		private record Block(long handle, long address, long size) {
		}

		private final ArrayDeque<Block> free = new ArrayDeque<>();
		private final List<Block> used = new ArrayList<>();
		private @Nullable Block current;
		private long at;

		Slice upload(ByteBuffer data, int alignment) {
			return this.copy(MemoryUtil.memAddress(data), data.remaining(), alignment);
		}

		Slice copy(long src, long length, int alignment) {
			long offset = (this.at + alignment - 1) & -(long) alignment;
			if (this.current == null || offset + length > this.current.size) {
				this.current = this.take(Math.max(BLOCK, length));
				offset = 0;
			}
			MemoryUtil.memCopy(src, this.current.address + offset, length);
			this.at = offset + length;
			return new Slice(this.current.handle, offset);
		}

		private Block take(long size) {
			Block b = size <= BLOCK ? this.free.poll() : null;
			if (b == null) {
				long h = Native.bufferNew(MetalEncoder.this.ctx, size);
				b = new Block(h, Native.bufferContents(h), size);
			}
			this.used.add(b);
			return b;
		}

		void endSubmit() {
			List<Block> done = new ArrayList<>(this.used);
			this.used.clear();
			this.current = null;
			this.at = 0;
			MetalEncoder.this.afterGpuFinishes(() -> {
				for (Block b : done) {
					if (b.size == BLOCK) this.free.add(b);
					else Native.release(b.handle);
				}
			});
		}
	}
}
