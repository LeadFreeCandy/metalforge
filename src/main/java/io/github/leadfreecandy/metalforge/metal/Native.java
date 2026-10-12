// Ported from mcopt (https://github.com/noahdunnagan/mcopt), Apache-2.0, Copyright 2026 Noah Dunnagan. Trimmed to the
// backend's calls; adds evo_layer_attach (GLFW window glue, native/evometal.m).
package io.github.leadfreecandy.metalforge.metal;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Downcalls into libmetalforge.dylib (mcopt's mcmetal.m). Every pointer crosses as a long (arm64 passes both in x registers).
 * Encoder calls are linked "critical": no thread-state transition, so a draw costs a few nanoseconds of glue.
 */
final class Native {
	private static final Linker LINKER = Linker.nativeLinker();
	private static final SymbolLookup LIB = SymbolLookup.libraryLookup(extract(), Arena.global());

	private static final MethodHandle CREATE = fn("mc_create", false, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT);
	private static final MethodHandle MAX_BUFFER_LENGTH = fn("mc_max_buffer_length", true, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle RELEASE = fn("mc_release", false, null, JAVA_LONG);
	private static final MethodHandle BUFFER_NEW = fn("mc_buffer_new", false, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle BUFFER_CONTENTS = fn("mc_buffer_contents", true, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle TEXTURE_NEW = fn("mc_texture_new", false, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle TEXTURE_VIEW = fn("mc_texture_view", false, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT);
	private static final MethodHandle TEXTURE_BUFFER = fn("mc_texture_buffer", false, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle SAMPLER_NEW = fn("mc_sampler_new", false, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_FLOAT);
	private static final MethodHandle LIBRARY_NEW = fn("mc_library_new", false, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle PIPELINE_NEW = fn("mc_pipeline_new", false, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle DEPTH_STATE_NEW = fn("mc_depth_state_new", false, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT);
	private static final MethodHandle ENC_NEW = fn("mc_enc_new", false, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle ENC_COMMIT = fn("mc_enc_commit", false, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle CMD_WAIT = fn("mc_cmd_wait", false, null, JAVA_LONG);
	private static final MethodHandle CMD_DONE = fn("mc_cmd_done", true, JAVA_INT, JAVA_LONG);
	private static final MethodHandle ENC_EMPTY = fn("mc_enc_empty", true, JAVA_INT, JAVA_LONG);
	private static final MethodHandle CMD_GPU_US = fn("mc_cmd_gpu_us", false, JAVA_DOUBLE, JAVA_LONG);
	private static final MethodHandle BLIT_BUFFER = fn("mc_blit_buffer", false, null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle BLIT_BUFFER_TO_TEXTURE = fn("mc_blit_buffer_to_texture", false, null,
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle BLIT_TEXTURE_TO_BUFFER = fn("mc_blit_texture_to_buffer", false, null,
		JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle BLIT_TEXTURE_TO_TEXTURE = fn("mc_blit_texture_to_texture", false, null,
		JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle RENDER_BEGIN = fn("mc_render_begin", false, JAVA_INT,
		JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_FLOAT, JAVA_INT, JAVA_INT);
	private static final MethodHandle DISCARD = fn("mc_discard", true, null, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle R_PIPELINE = fn("mc_r_pipeline", true, null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_FLOAT, JAVA_FLOAT, JAVA_INT);
	private static final MethodHandle R_BUFFER = fn("mc_r_buffer", true, null, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle R_VERTEX_BUFFER = fn("mc_r_vertex_buffer", true, null, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle R_BYTES = fn("mc_r_bytes", true, null, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT);
	private static final MethodHandle R_TEXTURE = fn("mc_r_texture", true, null, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle R_SCISSOR = fn("mc_r_scissor", true, null, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle R_INDEX = fn("mc_r_index", true, null, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle R_DRAW = fn("mc_r_draw", true, null, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle R_DRAW_INDEXED = fn("mc_r_draw_indexed", true, null, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle R_MULTI_DRAW_INDEXED_SEPARATE = fn("mc_r_multi_draw_indexed_separate", true, null, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT);
	private static final MethodHandle CLEAR_RECT = fn("mc_clear_rect", false, null, JAVA_LONG, JAVA_LONG, JAVA_LONG,
		JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle LAYER_CONFIGURE = fn("mc_layer_configure", false, null, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT);
	private static final MethodHandle LAYER_NEXT = fn("mc_layer_next", false, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle PRESENT = fn("mc_present", false, null, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle PRESENT_QUEUED_ACQUIRE = fn("mc_present_queued_acquire", false, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle PACE = fn("mc_pace", false, JAVA_INT, JAVA_DOUBLE);
	private static final MethodHandle PACE_ADAPT = fn("mc_pace_adapt", false, null, JAVA_INT);
	private static final MethodHandle SLEEP_PRECISE = fn("mc_sleep_precise", false, null, JAVA_LONG);
	private static final MethodHandle LAYER_ATTACH = fn("evo_layer_attach", false, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle LAYER_SCALE = fn("evo_layer_scale", false, null, JAVA_LONG, JAVA_LONG);
	private static final MethodHandle PACE_DISPLAY = fn("evo_pace_display", false, null, JAVA_INT);
	private static final MethodHandle VIEW_DISPLAY = fn("evo_view_display", false, JAVA_INT, JAVA_LONG);
	private static final MethodHandle PACE_START = fn("evo_pace_start", false, null);
	private static final MethodHandle SHOWN = fn("evo_shown", false, JAVA_LONG, JAVA_INT);
	private static final MethodHandle NOW = fn("evo_now", true, JAVA_DOUBLE);
	private static final MethodHandle NEXT_REFRESH = fn("evo_next_refresh", true, JAVA_DOUBLE);
	private static final MethodHandle REFRESH_PERIOD = fn("evo_refresh_period", true, JAVA_DOUBLE);
	private static final MethodHandle GPU_LATENCY = fn("evo_gpu_latency", true, JAVA_DOUBLE);
	private static final MethodHandle PRESENT_TARGET = fn("evo_present_target", true, null, JAVA_DOUBLE);
	private static final MethodHandle R_VIEWPORT = fn("evo_r_viewport", true, null, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);

	private Native() {
	}

	private static Path extract() {
		try (InputStream in = Native.class.getResourceAsStream("/natives/libmetalforge.dylib")) {
			if (in == null) throw new IllegalStateException("libmetalforge.dylib missing from the jar");
			Path dir = Files.createTempDirectory("metalforgemetal");
			Path lib = dir.resolve("libmetalforge.dylib");
			Files.copy(in, lib, StandardCopyOption.REPLACE_EXISTING);
			lib.toFile().deleteOnExit();
			dir.toFile().deleteOnExit();
			return lib;
		} catch (IOException e) {
			throw new IllegalStateException("can't extract libmetalforge.dylib", e);
		}
	}

	private static MethodHandle fn(String name, boolean critical, MemoryLayout result, MemoryLayout... args) {
		FunctionDescriptor d = result == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(result, args);
		var symbol = LIB.find(name).orElseThrow(() -> new IllegalStateException("missing native symbol " + name));
		return critical ? LINKER.downcallHandle(symbol, d, Linker.Option.critical(false)) : LINKER.downcallHandle(symbol, d);
	}

	static long create(long name, int nameCap, long err, int errCap) { try { return (long) CREATE.invokeExact(name, nameCap, err, errCap); } catch (Throwable t) { throw rethrow(t); } }
	static long maxBufferLength(long ctx) { try { return (long) MAX_BUFFER_LENGTH.invokeExact(ctx); } catch (Throwable t) { throw rethrow(t); } }
	static void release(long obj) { try { RELEASE.invokeExact(obj); } catch (Throwable t) { throw rethrow(t); } }
	static long bufferNew(long ctx, long size) { try { return (long) BUFFER_NEW.invokeExact(ctx, size); } catch (Throwable t) { throw rethrow(t); } }
	static long bufferContents(long buffer) { try { return (long) BUFFER_CONTENTS.invokeExact(buffer); } catch (Throwable t) { throw rethrow(t); } }
	static long textureNew(long ctx, int format, int w, int h, int layers, int mips, int usage, int cube) { try { return (long) TEXTURE_NEW.invokeExact(ctx, format, w, h, layers, mips, usage, cube); } catch (Throwable t) { throw rethrow(t); } }
	static long textureView(long texture, int baseMip, int mips) { try { return (long) TEXTURE_VIEW.invokeExact(texture, baseMip, mips); } catch (Throwable t) { throw rethrow(t); } }
	static long textureBuffer(long ctx, long buffer, int format, long offset, long length, int texelSize) { try { return (long) TEXTURE_BUFFER.invokeExact(ctx, buffer, format, offset, length, texelSize); } catch (Throwable t) { throw rethrow(t); } }
	static long samplerNew(long ctx, int u, int v, int min, int mag, int mip, int aniso, float maxLod) { try { return (long) SAMPLER_NEW.invokeExact(ctx, u, v, min, mag, mip, aniso, maxLod); } catch (Throwable t) { throw rethrow(t); } }
	static long libraryNew(long ctx, long source, long err, int errCap) { try { return (long) LIBRARY_NEW.invokeExact(ctx, source, err, errCap); } catch (Throwable t) { throw rethrow(t); } }
	static long pipelineNew(long ctx, long vlib, long vname, long flib, long fname, long desc, long err, int errCap) { try { return (long) PIPELINE_NEW.invokeExact(ctx, vlib, vname, flib, fname, desc, err, errCap); } catch (Throwable t) { throw rethrow(t); } }
	static long depthStateNew(long ctx, int compare, int write) { try { return (long) DEPTH_STATE_NEW.invokeExact(ctx, compare, write); } catch (Throwable t) { throw rethrow(t); } }
	static long encNew(long ctx) { try { return (long) ENC_NEW.invokeExact(ctx); } catch (Throwable t) { throw rethrow(t); } }
	static long encCommit(long enc) { try { return (long) ENC_COMMIT.invokeExact(enc); } catch (Throwable t) { throw rethrow(t); } }
	static void cmdWait(long cmd) { try { CMD_WAIT.invokeExact(cmd); } catch (Throwable t) { throw rethrow(t); } }
	static boolean cmdDone(long cmd) { try { return (int) CMD_DONE.invokeExact(cmd) != 0; } catch (Throwable t) { throw rethrow(t); } }
	static boolean encEmpty(long enc) { try { return (int) ENC_EMPTY.invokeExact(enc) != 0; } catch (Throwable t) { throw rethrow(t); } }
	static double cmdGpuMicros(long cmd) { try { return (double) CMD_GPU_US.invokeExact(cmd); } catch (Throwable t) { throw rethrow(t); } }
	static void blitBuffer(long enc, long src, long srcOff, long dst, long dstOff, long size) { try { BLIT_BUFFER.invokeExact(enc, src, srcOff, dst, dstOff, size); } catch (Throwable t) { throw rethrow(t); } }
	static void blitBufferToTexture(long enc, long src, long off, int bytesPerRow, int bytesPerImage, long dst, int slice, int mip, int x, int y, int w, int h) { try { BLIT_BUFFER_TO_TEXTURE.invokeExact(enc, src, off, bytesPerRow, bytesPerImage, dst, slice, mip, x, y, w, h); } catch (Throwable t) { throw rethrow(t); } }
	static void blitTextureToBuffer(long enc, long src, int mip, int x, int y, int w, int h, long dst, long off, int bytesPerRow) { try { BLIT_TEXTURE_TO_BUFFER.invokeExact(enc, src, mip, x, y, w, h, dst, off, bytesPerRow); } catch (Throwable t) { throw rethrow(t); } }
	static void blitTextureToTexture(long enc, long src, long dst, int mip, int dx, int dy, int sx, int sy, int w, int h) { try { BLIT_TEXTURE_TO_TEXTURE.invokeExact(enc, src, dst, mip, dx, dy, sx, sy, w, h); } catch (Throwable t) { throw rethrow(t); } }
	/** 0: opened a new render encoder; 1: continued the open one (same attachments); 2: continued it after clearing depth in place. */
	static int renderBegin(long enc, int count, long colors, long clears, long depth, int clearDepth, float depthValue, int w, int h) { try { return (int) RENDER_BEGIN.invokeExact(enc, count, colors, clears, depth, clearDepth, depthValue, w, h); } catch (Throwable t) { throw rethrow(t); } }
	static void discard(long enc, long texture) { try { DISCARD.invokeExact(enc, texture); } catch (Throwable t) { throw rethrow(t); } }
	static void pipeline(long enc, long pso, long depth, int cull, int wireframe, float biasConstant, float biasSlope, int primitive) { try { R_PIPELINE.invokeExact(enc, pso, depth, cull, wireframe, biasConstant, biasSlope, primitive); } catch (Throwable t) { throw rethrow(t); } }
	static void buffer(long enc, int index, long buffer, long offset) { try { R_BUFFER.invokeExact(enc, index, buffer, offset); } catch (Throwable t) { throw rethrow(t); } }
	static void vertexBuffer(long enc, int index, long buffer, long offset) { try { R_VERTEX_BUFFER.invokeExact(enc, index, buffer, offset); } catch (Throwable t) { throw rethrow(t); } }
	static void bytes(long enc, int index, long bytes, int length) { try { R_BYTES.invokeExact(enc, index, bytes, length); } catch (Throwable t) { throw rethrow(t); } }
	static void texture(long enc, int index, long texture, long sampler) { try { R_TEXTURE.invokeExact(enc, index, texture, sampler); } catch (Throwable t) { throw rethrow(t); } }
	static void scissor(long enc, int x, int y, int w, int h) { try { R_SCISSOR.invokeExact(enc, x, y, w, h); } catch (Throwable t) { throw rethrow(t); } }
	static void index(long enc, long buffer, int intIndices) { try { R_INDEX.invokeExact(enc, buffer, intIndices); } catch (Throwable t) { throw rethrow(t); } }
	static void draw(long enc, int count, int instances, int first, int firstInstance) { try { R_DRAW.invokeExact(enc, count, instances, first, firstInstance); } catch (Throwable t) { throw rethrow(t); } }
	static void drawIndexed(long enc, int count, int instances, int firstIndex, int baseVertex, int firstInstance) { try { R_DRAW_INDEXED.invokeExact(enc, count, instances, firstIndex, baseVertex, firstInstance); } catch (Throwable t) { throw rethrow(t); } }
	/** GL-style separate arrays (Sodium's MultiDrawBatch): byte offsets into the index buffer, index counts, base vertices. */
	static void multiDrawIndexedSeparate(long enc, long offsets, long counts, long baseVertices, int drawCount) { try { R_MULTI_DRAW_INDEXED_SEPARATE.invokeExact(enc, offsets, counts, baseVertices, drawCount); } catch (Throwable t) { throw rethrow(t); } }
	static void clearRect(long enc, long color, long depth, float r, float g, float b, float a, float depthValue, int x, int y, int w, int h, int mip) { try { CLEAR_RECT.invokeExact(enc, color, depth, r, g, b, a, depthValue, x, y, w, h, mip); } catch (Throwable t) { throw rethrow(t); } }
	static void layerConfigure(long ctx, long layer, int w, int h, int vsync) { try { LAYER_CONFIGURE.invokeExact(ctx, layer, w, h, vsync); } catch (Throwable t) { throw rethrow(t); } }
	static long layerNext(long layer) { try { return (long) LAYER_NEXT.invokeExact(layer); } catch (Throwable t) { throw rethrow(t); } }
	static void present(long enc, long drawable, long texture) { try { PRESENT.invokeExact(enc, drawable, texture); } catch (Throwable t) { throw rethrow(t); } }
	/** mcopt's present queue (acquire mode): copies src into a staging slot; a second queue acquires the drawable and presents. 0 = skipped. */
	static int presentQueuedAcquire(long enc, long layer, long texture, long cadence) { try { return (int) PRESENT_QUEUED_ACQUIRE.invokeExact(enc, layer, texture, cadence); } catch (Throwable t) { throw rethrow(t); } }
	static boolean pace(double marginSeconds) { try { return (int) PACE.invokeExact(marginSeconds) != 0; } catch (Throwable t) { throw rethrow(t); } }
	static void paceAdapt(int on) { try { PACE_ADAPT.invokeExact(on); } catch (Throwable t) { throw rethrow(t); } }
	static void sleepPrecise(long ns) { try { SLEEP_PRECISE.invokeExact(ns); } catch (Throwable t) { throw rethrow(t); } }
	static long layerAttach(long nsview) { try { return (long) LAYER_ATTACH.invokeExact(nsview); } catch (Throwable t) { throw rethrow(t); } }
	static long shown(int k) { try { return (long) SHOWN.invokeExact(k); } catch (Throwable t) { throw rethrow(t); } }
	static void paceStart() { try { PACE_START.invokeExact(); } catch (Throwable t) { throw rethrow(t); } }
	static double now() { try { return (double) NOW.invokeExact(); } catch (Throwable t) { throw rethrow(t); } }
	static double nextRefresh() { try { return (double) NEXT_REFRESH.invokeExact(); } catch (Throwable t) { throw rethrow(t); } }
	static double refreshPeriod() { try { return (double) REFRESH_PERIOD.invokeExact(); } catch (Throwable t) { throw rethrow(t); } }
	static double gpuLatency() { try { return (double) GPU_LATENCY.invokeExact(); } catch (Throwable t) { throw rethrow(t); } }
	static void presentTarget(double t) { try { PRESENT_TARGET.invokeExact(t); } catch (Throwable t2) { throw rethrow(t2); } }
	static void paceDisplay(int display) { try { PACE_DISPLAY.invokeExact(display); } catch (Throwable t) { throw rethrow(t); } }
	static int viewDisplay(long nsview) { try { return (int) VIEW_DISPLAY.invokeExact(nsview); } catch (Throwable t) { throw rethrow(t); } }
	static void viewport(long enc, int x, int y, int w, int h) { try { R_VIEWPORT.invokeExact(enc, x, y, w, h); } catch (Throwable t) { throw rethrow(t); } }
	static void layerScale(long layer, long nsview) { try { LAYER_SCALE.invokeExact(layer, nsview); } catch (Throwable t) { throw rethrow(t); } }

	private static RuntimeException rethrow(Throwable t) {
		if (t instanceof RuntimeException r) return r;
		if (t instanceof Error e) throw e;
		return new IllegalStateException(t);
	}
}
