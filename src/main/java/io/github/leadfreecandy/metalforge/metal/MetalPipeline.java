// The SPIR-V -> MSL half is ported from mcopt (https://github.com/noahdunnagan/mcopt), Apache-2.0, Copyright 2026 Noah Dunnagan.
// 26.1.2 hands backends GLSL by name rather than 26.3's SPIR-V with indexed bind groups, so the GLSL -> SPIR-V front end, the
// name -> slot reflection and the per-attachment pipeline states are new.
package io.github.leadfreecandy.metalforge.metal;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;
import org.lwjgl.util.spvc.SpvcReflectedResource;
import org.slf4j.Logger;

import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.util.spvc.Spvc.*;

/**
 * A pipeline: the game's GLSL, given explicit interface locations (GlslFixup), compiled to SPIR-V by shaderc, translated to
 * Metal Shading Language by SPIRV-Cross and compiled by Metal. Uniform blocks, samplers and texel buffers get one MSL slot per
 * name, shared by both stages (buffer, texture and sampler slots are separate namespaces in Metal, so a block and a sampler may
 * share a number). Vertex buffers live from slot 27. Vertex Y is flipped so render targets keep OpenGL's row order.
 * Metal bakes attachment formats into the pipeline state, so states are made per (color format, depth format) on first use.
 */
final class MetalPipeline implements CompiledRenderPipeline {
	private static final Logger LOGGER = LogUtils.getLogger();
	/** -Dmetalforge.metal.dumpMsl=DIR writes every pipeline's GLSL (after fix-ups) and generated MSL there. */
	private static final @Nullable String DUMP_DIR = System.getProperty("metalforge.metal.dumpMsl", System.getenv("METALFORGE_METAL_DUMP"));

	static final MetalPipeline INVALID = new MetalPipeline();

	/** Vertex input: shader attribute names (location = index), stride, per-attribute byte offset and MTLVertexFormat. */
	record VertexLayout(List<String> names, int stride, int[] offsets, int[] formats) {
		static VertexLayout of(VertexFormat format) {
			List<VertexFormatElement> elements = format.getElements();
			int[] offsets = new int[elements.size()], formats = new int[elements.size()];
			for (int i = 0; i < elements.size(); i++) {
				offsets[i] = format.getOffset(elements.get(i));
				formats[i] = MetalConst.vertexFormat(elements.get(i));
			}
			return new VertexLayout(format.getElementAttributeNames(), format.getVertexSize(), offsets, formats);
		}
	}

	/** Loose (non-block, non-opaque) uniforms, gathered into the uniform block LOOSE_BLOCK: name -> std140 offset, and its size. */
	static final String LOOSE_BLOCK = "EvoLoose";
	final Map<String, Integer> looseOffsets = new LinkedHashMap<>();
	int looseSize;

	final RenderPipeline info;
	String name;
	private VertexLayout layout;
	private final long ctx;
	private long vlib, flib;
	private String ventry = "", fentry = "";
	/** Uniform block / sampler / texel buffer name -> MSL slot. */
	final Map<String, Integer> slots = new LinkedHashMap<>();
	/** Slot -> kind (0 uniform block, 1 sampler, 2 texel buffer) and, for texel buffers, the format. */
	final int[] slotKind = new int[MetalConst.MAX_SLOTS];
	/** Texel buffer slots: MTLPixelFormat and bytes per texel. */
	final int[] slotTexelFormat = new int[MetalConst.MAX_SLOTS], slotTexelSize = new int[MetalConst.MAX_SLOTS];
	final String[] slotName = new String[MetalConst.MAX_SLOTS];
	int slotCount;
	final long depthState;
	final boolean cull, wireframe;
	final float depthBiasConstant, depthBiasSlope;
	final int primitive;
	private final Long2LongOpenHashMap states = new Long2LongOpenHashMap();
	private final boolean valid;

	private MetalPipeline() {
		this.info = null;
		this.name = "invalid";
		this.ctx = 0;
		this.depthState = 0;
		this.cull = this.wireframe = false;
		this.depthBiasConstant = this.depthBiasSlope = 0;
		this.primitive = 0;
		this.valid = false;
	}

	private MetalPipeline(long ctx, RenderPipeline info) {
		this.ctx = ctx;
		this.info = info;
		this.name = info.getLocation().toString();
		this.cull = info.isCull();
		this.wireframe = info.getPolygonMode() == PolygonMode.WIREFRAME;
		DepthStencilState depth = info.getDepthStencilState();
		this.depthBiasConstant = depth != null ? depth.depthBiasConstant() : 0;
		this.depthBiasSlope = depth != null ? depth.depthBiasScaleFactor() : 0;
		this.primitive = MetalConst.primitive(info.getVertexFormatMode());
		// GL with the depth test disabled neither tests nor writes depth.
		this.depthState = Native.depthStateNew(ctx, depth == null ? 7 : MetalConst.compare(depth.depthTest()), depth != null && depth.writeDepth() ? 1 : 0);
		this.valid = true;
		this.layout = VertexLayout.of(info.getVertexFormat());
	}

	@Override
	public boolean isValid() {
		return this.valid;
	}

	/** The MSL slot of a uniform block / sampler / texel buffer, or -1 when neither stage uses it. */
	int slotOf(String name) {
		Integer s = this.slots.get(name);
		return s == null ? -1 : s;
	}

	static MetalPipeline compile(long ctx, RenderPipeline pipeline, ShaderSource source) {
		String vsrc = source.get(pipeline.getVertexShader(), ShaderType.VERTEX);
		String fsrc = source.get(pipeline.getFragmentShader(), ShaderType.FRAGMENT);
		if (vsrc == null || fsrc == null) {
			// Before resources load (the Mojang logo) the shader manager has nothing yet; GL draws nothing then either.
			// Not cached (null): compiled once the sources exist.
			LOGGER.debug("Metal: no shader source yet for pipeline {}", pipeline.getLocation());
			return null;
		}
		MetalPipeline p = new MetalPipeline(ctx, pipeline);
		// slots: the pipeline's declared uniforms first (in order), then samplers, then anything else either stage declares
		for (RenderPipeline.UniformDescription u : pipeline.getUniforms()) p.slot(u.name(), u.type() == UniformType.TEXEL_BUFFER ? 2 : 0, u.textureFormat());
		for (String s : pipeline.getSamplers()) p.slot(s, 1, null);
		return p.compileSources(GlslPreprocessor.injectDefines(vsrc, pipeline.getShaderDefines()), GlslPreprocessor.injectDefines(fsrc, pipeline.getShaderDefines()));
	}

	/**
	 * A pipeline from sources the caller assembled (e.g. Sodium's terrain shaders, preprocessed by Sodium), with fixed-function
	 * state taken from stateFrom and its own vertex layout. texelBuffers: sampler-buffer uniforms and their texel formats.
	 */
	static MetalPipeline compileCustom(long ctx, RenderPipeline stateFrom, String name, String vsrc, String fsrc, VertexLayout layout,
		Map<String, int[]> texelBuffers) {
		MetalPipeline p = new MetalPipeline(ctx, stateFrom, name, layout);
		texelBuffers.forEach((n, f) -> p.slot(n, 2, f[0], f[1]));
		return p.compileSources(vsrc, fsrc);
	}

	private MetalPipeline(long ctx, RenderPipeline stateFrom, String name, VertexLayout layout) {
		this(ctx, stateFrom);
		this.name = name;
		this.layout = layout;
	}

	private MetalPipeline compileSources(String vsrc, String fsrc) {
		MetalPipeline p = this;
		try {
			GlslFixup.Loose loose = GlslFixup.loose(vsrc, fsrc, LOOSE_BLOCK);
			vsrc = loose.vertex();
			fsrc = loose.fragment();
			if (loose.size() > 0) {
				p.looseOffsets.putAll(loose.offsets());
				p.looseSize = loose.size();
			}
			java.util.function.ToIntFunction<String> block = n -> { p.slot(n, 0, null); return p.slots.get(n); };
			java.util.function.ToIntFunction<String> sampler = n -> { p.slot(n, p.slots.containsKey(n) ? p.slotKind[p.slots.get(n)] : 1, null); return p.slots.get(n); };
			GlslFixup.Fixed glsl = GlslFixup.apply(GlslFixup.bindings(vsrc, block, sampler), GlslFixup.bindings(fsrc, block, sampler), p.layout.names());
			ByteBuffer vspv = spirv(glsl.vertex(), true, p.name);
			ByteBuffer fspv = spirv(glsl.fragment(), false, p.name);
			{
				Translated v = translate(p, vspv, true);
				Translated f = translate(p, fspv, false);
				if (DUMP_DIR != null) dump(p.name, glsl, v, f);
				try (MemoryStack stack = MemoryStack.stackPush()) {
					long err = stack.nmalloc(1, 4096);
					p.vlib = Native.libraryNew(ctx, MemoryUtil.memAddress(stack.UTF8(v.msl)), err, 4096);
					if (p.vlib == 0) throw new IllegalStateException("vertex: " + MemoryUtil.memUTF8(err) + "\n" + v.msl);
					p.flib = Native.libraryNew(ctx, MemoryUtil.memAddress(stack.UTF8(f.msl)), err, 4096);
					if (p.flib == 0) throw new IllegalStateException("fragment: " + MemoryUtil.memUTF8(err) + "\n" + f.msl);
				}
				p.ventry = v.entry;
				p.fentry = f.entry;
			}
			// the common target (RGBA8 color + 32-bit depth, or no depth) now, off the first frame that uses it
			p.state(MetalConst.pixelFormat(TextureFormat.RGBA8), p.info.getDepthStencilState() != null ? MetalConst.D32F : 0);
			return p;
		} catch (RuntimeException e) {
			LOGGER.error("Metal: couldn't compile pipeline {}: {}", p.name, e.getMessage());
			return INVALID;
		}
	}

	private void slot(String name, int kind, @Nullable TextureFormat format) {
		this.slot(name, kind, format == null ? 0 : MetalConst.pixelFormat(format), format == null ? 0 : format.pixelSize());
	}

	private void slot(String name, int kind, int texelFormat, int texelSize) {
		if (this.slots.containsKey(name)) return;
		if (this.slotCount >= MetalConst.MAX_SLOTS) throw new IllegalStateException("too many uniforms");
		int s = this.slotCount++;
		this.slots.put(name, s);
		this.slotKind[s] = kind;
		this.slotTexelFormat[s] = texelFormat;
		this.slotTexelSize[s] = texelSize;
		this.slotName[s] = name;
	}

	/** The pipeline state for these attachment formats (MTLPixelFormat; 0 = none), made on first use. */
	long state(int colorFormat, int depthFormat) {
		long key = (long) colorFormat << 32 | depthFormat;
		long pso = this.states.get(key);
		if (pso != 0) return pso;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			long err = stack.nmalloc(1, 4096);
			pso = Native.pipelineNew(this.ctx, this.vlib, MemoryUtil.memAddress(stack.UTF8(this.ventry)), this.flib,
				MemoryUtil.memAddress(stack.UTF8(this.fentry)), this.describe(stack, colorFormat, depthFormat), err, 4096);
			if (pso == 0) throw new IllegalStateException("Metal: pipeline state for " + this.name + ": " + MemoryUtil.memUTF8(err));
		}
		this.states.put(key, pso);
		return pso;
	}

	/** Packs the layout into the int array mc_pipeline_new reads (see mcmetal.m). */
	private long describe(MemoryStack stack, int colorFormat, int depthFormat) {
		VertexLayout l = this.layout;
		int n = l.names().size();
		IntBuffer d = stack.mallocInt(5 + 3 + n * 4 + 9);
		boolean vertexInput = n > 0;
		d.put(vertexInput ? 1 : 0);
		if (vertexInput) d.put(MetalConst.VERTEX_BUFFER_BASE).put(l.stride()).put(0);
		d.put(n);
		for (int i = 0; i < n; i++) d.put(i).put(MetalConst.VERTEX_BUFFER_BASE).put(l.offsets()[i]).put(l.formats()[i]);
		if (colorFormat == 0) {
			d.put(0);
		} else {
			d.put(1);
			ColorTargetState c = this.info.getColorTargetState();
			// MTLColorWriteMask is ABGR-ordered (red = 8); Blaze3D's is RGBA-ordered (red = 1).
			int mask = (c.writeRed() ? 8 : 0) | (c.writeGreen() ? 4 : 0) | (c.writeBlue() ? 2 : 0) | (c.writeAlpha() ? 1 : 0);
			d.put(colorFormat).put(mask);
			if (c.blendFunction().isPresent()) {
				BlendFunction b = c.blendFunction().get();
				d.put(1).put(MetalConst.blendFactor(b.sourceColor())).put(MetalConst.blendFactor(b.destColor())).put(0)
					.put(MetalConst.blendFactor(b.sourceAlpha())).put(MetalConst.blendFactor(b.destAlpha())).put(0);
			} else {
				d.put(new int[] {0, 1, 0, 0, 1, 0, 0});
			}
		}
		d.put(depthFormat);
		d.put(MetalConst.topologyClass(this.info.getVertexFormatMode()));
		return MemoryUtil.memAddress(d.flip());
	}

	void close() {
		for (long pso : this.states.values()) Native.release(pso);
		this.states.clear();
		if (this.vlib != 0) Native.release(this.vlib);
		if (this.flib != 0) Native.release(this.flib);
		if (this.depthState != 0) Native.release(this.depthState);
		this.vlib = this.flib = 0;
	}

	// ---- GLSL -> SPIR-V (shaderc) ----

	private static long compiler, options;

	private static synchronized ByteBuffer spirv(String glsl, boolean vertex, String name) {
		if (compiler == 0) {
			compiler = shaderc_compiler_initialize();
			options = shaderc_compile_options_initialize();
			// OpenGL-flavored SPIR-V: keeps gl_VertexID & co. and GL's sampler model; bindings auto-assigned (reflected after).
			shaderc_compile_options_set_target_env(options, shaderc_target_env_opengl, shaderc_env_version_opengl_4_5);
			shaderc_compile_options_set_auto_bind_uniforms(options, true);
			shaderc_compile_options_set_auto_map_locations(options, true);
			// Bindings are explicit (GlslFixup.bindings: binding = MSL slot); auto-binding only covers anything that slipped past.
			shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_zero);   // keep OpName: slots are matched by name
		}
		long result = shaderc_compile_into_spv(compiler, glsl, vertex ? shaderc_glsl_vertex_shader : shaderc_glsl_fragment_shader,
			name + (vertex ? ".vsh" : ".fsh"), "main", options);
		try {
			if (result == 0 || shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
				throw new IllegalStateException((vertex ? "vertex" : "fragment") + " GLSL: " + (result == 0 ? "no result" : shaderc_result_get_error_message(result)));
			}
			ByteBuffer bytes = shaderc_result_get_bytes(result);
			ByteBuffer copy = MemoryUtil.memAlloc(bytes.remaining());
			copy.put(bytes).flip();
			return copy;
		} finally {
			if (result != 0) shaderc_result_release(result);
		}
	}

	// ---- SPIR-V -> MSL (SPIRV-Cross), as in mcopt ----

	private record Translated(String msl, String entry) {
	}

	private static Translated translate(MetalPipeline p, ByteBuffer spv, boolean vertex) {
		int model = vertex ? Spv.SpvExecutionModelVertex : Spv.SpvExecutionModelFragment;
		IntBuffer spirv = spv.asIntBuffer();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer out = stack.callocPointer(1);
			check(0, spvc_context_create(out), "context");
			long context = out.get(0);
			try {
				check(context, spvc_context_parse_spirv(context, spirv, spirv.remaining(), out), "parse");
				long ir = out.get(0);
				check(context, spvc_context_create_compiler(context, SPVC_BACKEND_MSL, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, out), "compiler");
				long compiler = out.get(0);
				check(context, spvc_compiler_create_compiler_options(compiler, out), "options");
				long opts = out.get(0);
				spvc_compiler_options_set_uint(opts, SPVC_COMPILER_OPTION_MSL_VERSION, 30000);
				spvc_compiler_options_set_uint(opts, SPVC_COMPILER_OPTION_MSL_PLATFORM, SPVC_MSL_PLATFORM_MACOS);
				spvc_compiler_options_set_bool(opts, SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true);
				if (vertex) spvc_compiler_options_set_bool(opts, SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true);
				check(context, spvc_compiler_install_compiler_options(compiler, opts), "install options");
				// reflect resources: name -> slot, (set, binding) -> slot
				check(context, spvc_compiler_create_shader_resources(compiler, out), "resources");
				long resources = out.get(0);
				java.util.Set<Long> used = new java.util.HashSet<>();
				bindAll(stack, p, compiler, resources, SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, model, true, used);
				bindAll(stack, p, compiler, resources, SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, model, false, used);
				check(context, spvc_compiler_compile(compiler, out), "compile");
				String msl = MemoryUtil.memUTF8(out.get(0));
				return new Translated(msl, spvc_compiler_get_cleansed_entry_point_name(compiler, "main", model));
			} finally {
				spvc_context_destroy(context);
				MemoryUtil.memFree(spv);
			}
		}
	}

	private static void bindAll(MemoryStack stack, MetalPipeline p, long compiler, long resources, int type, int model, boolean block, java.util.Set<Long> used) {
		PointerBuffer list = stack.callocPointer(1), count = stack.callocPointer(1);
		spvc_resources_get_resource_list_for_type(resources, type, list, count);
		SpvcReflectedResource.Buffer rs = SpvcReflectedResource.create(list.get(0), (int) count.get(0));
		for (SpvcReflectedResource r : rs) {
			// GL identifies a uniform block by its block (type) name; samplers by the variable name.
			String name = block ? spvc_compiler_get_name(compiler, r.base_type_id()) : r.nameString();
			if (name == null || name.isEmpty()) name = r.nameString();
			if (name == null || name.isEmpty()) throw new IllegalStateException("unnamed " + (block ? "uniform block" : "sampler") + " (SPIR-V names stripped?)");
			if (name == null || name.isEmpty()) throw new IllegalStateException("unnamed " + (block ? "uniform block" : "sampler") + " (SPIR-V names stripped?)");
			if (!p.slots.containsKey(name)) p.slot(name, block ? 0 : 1, null);
			int slot = p.slots.get(name);
			int set = spvc_compiler_get_decoration(compiler, r.id(), Spv.SpvDecorationDescriptorSet);
			int binding = spvc_compiler_get_decoration(compiler, r.id(), Spv.SpvDecorationBinding);
			if (binding != slot) throw new IllegalStateException(name + ": binding " + binding + " but slot " + slot + " (explicit binding missing?)");
			if (!used.add((long) set << 32 | binding)) continue;
			SpvcMslResourceBinding b = SpvcMslResourceBinding.calloc(stack);
			spvc_msl_resource_binding_init(b);
			b.stage(model).desc_set(set).binding(binding).msl_buffer(slot).msl_texture(slot).msl_sampler(slot);
			spvc_compiler_msl_add_resource_binding(compiler, b);
		}
	}

	private static void check(long context, int result, String step) {
		if (result != 0) {
			throw new IllegalStateException("SPIRV-Cross " + step + " failed: " + (context != 0 ? spvc_context_get_last_error_string(context) : result));
		}
	}

	private static void dump(String name, GlslFixup.Fixed glsl, Translated v, Translated f) {
		try {
			Path dir = Path.of(DUMP_DIR);
			Files.createDirectories(dir);
			String file = name.replaceAll("[^A-Za-z0-9._-]", "_");
			Files.writeString(dir.resolve(file + ".vsh"), glsl.vertex());
			Files.writeString(dir.resolve(file + ".fsh"), glsl.fragment());
			Files.writeString(dir.resolve(file + ".vs.metal"), v.msl);
			Files.writeString(dir.resolve(file + ".fs.metal"), f.msl);
		} catch (Exception e) {
			LOGGER.warn("Metal: dumping {} failed: {}", name, e.toString());
		}
	}
}
