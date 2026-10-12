package io.github.leadfreecandy.metalforge.metal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes Minecraft 26.1.2's GLSL 330 (no layout locations; GL matches varyings by name and binds attributes by name with
 * glBindAttribLocation) compile to SPIR-V whose interfaces line up the way GL would link them. SPIR-V / SPIRV-Cross match
 * stages by location, and shaderc's auto-mapping numbers each stage on its own in declaration order, so a vertex shader
 * that declares its outputs in a different order than the fragment shader reads them would silently swap varyings.
 * <ul>
 * <li>vertex inputs: location = index of the same-named element in the pipeline's VertexFormat (what GlProgram.link
 * binds); inputs the format lacks become constants with GL's default attribute value (0, 0, 0, 1).</li>
 * <li>varyings: one name -> location map per pipeline, from the vertex shader's outputs, applied to both stages.</li>
 * <li>fragment outputs: locations in declaration order.</li>
 * </ul>
 */
final class GlslFixup {
	/** A global in/out declaration: optional qualifiers, direction, type, name, optional array size. */
	private static final Pattern DECL = Pattern.compile(
		"(?m)^[ \\t]*((?:(?:flat|smooth|noperspective|centroid|invariant|highp|mediump|lowp)\\s+)*)(in|out)\\s+(\\w+)\\s+(\\w+)\\s*(\\[[^\\]]*\\])?\\s*;");

	private GlslFixup() {
	}

	record Fixed(String vertex, String fragment, Map<String, Integer> varyings) {
	}

	private static final Pattern BLOCK = Pattern.compile("layout\\s*\\(\\s*std140\\s*\\)\\s*uniform\\s+(\\w+)");
	private static final Pattern SAMPLER = Pattern.compile("(?m)^([ \\t]*)uniform\\s+((?:highp\\s+|mediump\\s+|lowp\\s+)?[iu]?sampler\\w+)\\s+(\\w+)\\s*;");

	/**
	 * Explicit bindings from one pipeline-wide name -> slot table, so a block or sampler has the same slot in both stages
	 * (shaderc's auto-binding numbers each stage and each kind on its own). The binding number is the MSL slot.
	 */
	static String bindings(String src, java.util.function.ToIntFunction<String> blockSlot, java.util.function.ToIntFunction<String> samplerSlot) {
		StringBuilder out = new StringBuilder();
		Matcher m = BLOCK.matcher(src);
		while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement("layout(std140, binding = " + blockSlot.applyAsInt(m.group(1)) + ") uniform " + m.group(1)));
		m.appendTail(out);
		String s = out.toString();
		out = new StringBuilder();
		m = SAMPLER.matcher(s);
		while (m.find()) {
			m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + "layout(binding = " + samplerSlot.applyAsInt(m.group(3)) + ") uniform " + m.group(2) + " " + m.group(3) + ";"));
		}
		m.appendTail(out);
		return out.toString();
	}

	/** GLSL 330 has no location qualifiers on varyings nor binding qualifiers; 420 core has both and is a superset for these shaders. */
	private static String version410(String src) {
		return src.replaceFirst("#version\\s+330(\\s+core)?", "#version 420 core");
	}

	/** Identifiers that are reserved type names in Metal Shading Language (SPIRV-Cross passes local names through). */
	private static final Pattern MSL_RESERVED = Pattern.compile("\\bsampler\\b");

	static Fixed apply(String vertex, String fragment, List<String> elements) {
		vertex = MSL_RESERVED.matcher(version410(vertex)).replaceAll("sampler_evo");
		fragment = MSL_RESERVED.matcher(version410(fragment)).replaceAll("sampler_evo");
		Map<String, Integer> varyings = new LinkedHashMap<>();
		// vertex: inputs from the format, outputs numbered in declaration order (counting array/matrix slots)
		StringBuilder v = new StringBuilder();
		Matcher m = DECL.matcher(vertex);
		int nextVarying = 0;
		while (m.find()) {
			String quals = m.group(1), dir = m.group(2), type = m.group(3), name = m.group(4), array = m.group(5) == null ? "" : m.group(5);
			String repl;
			if (dir.equals("in")) {
				int loc = elements.indexOf(name);
				repl = loc >= 0 ? "layout(location = " + loc + ") " + quals + "in " + type + " " + name + array + ";"
					: "const " + type + " " + name + array + " = " + defaultValue(type) + ";";
			} else {
				varyings.put(name, nextVarying);
				repl = "layout(location = " + nextVarying + ") " + quals + "out " + type + " " + name + array + ";";
				nextVarying += slots(type, array);
			}
			m.appendReplacement(v, Matcher.quoteReplacement(repl));
		}
		m.appendTail(v);
		// fragment: inputs take the vertex shader's location for the same name; extra inputs (never written: undefined in GL too)
		// go after them; outputs numbered from 0
		StringBuilder f = new StringBuilder();
		m = DECL.matcher(fragment);
		int extra = nextVarying, nextOut = 0;
		while (m.find()) {
			String quals = m.group(1), dir = m.group(2), type = m.group(3), name = m.group(4), array = m.group(5) == null ? "" : m.group(5);
			int loc;
			if (dir.equals("in")) {
				Integer known = varyings.get(name);
				if (known != null) loc = known;
				else {
					loc = extra;
					extra += slots(type, array);
				}
			} else {
				loc = nextOut;
				nextOut += slots(type, array);
			}
			m.appendReplacement(f, Matcher.quoteReplacement("layout(location = " + loc + ") " + quals + dir + " " + type + " " + name + array + ";"));
		}
		m.appendTail(f);
		return new Fixed(v.toString(), f.toString(), varyings);
	}

	record Loose(String vertex, String fragment, Map<String, Integer> offsets, int size) {
	}

	private static final Pattern LOOSE = Pattern.compile(
		"(?m)^[ \\t]*uniform\\s+(float|int|uint|bool|vec2|vec3|vec4|ivec2|ivec3|ivec4|uvec2|uvec3|uvec4|mat3|mat4)\\s+(\\w+)\\s*;[^\\n]*$");

	/**
	 * Loose (non-block, non-opaque) uniforms, as Sodium's terrain shaders declare (u_RegionOffset, u_CurrentTime, u_RegionID):
	 * SPIR-V has no loose uniforms, so both stages get one identical std140 block holding all of them, in first-seen order.
	 */
	static Loose loose(String vertex, String fragment, String blockName) {
		Map<String, String> types = new LinkedHashMap<>();
		for (String src : new String[] {vertex, fragment}) {
			Matcher m = LOOSE.matcher(src);
			while (m.find()) types.putIfAbsent(m.group(2), m.group(1));
		}
		if (types.isEmpty()) return new Loose(vertex, fragment, Map.of(), 0);
		Map<String, Integer> offsets = new LinkedHashMap<>();
		StringBuilder block = new StringBuilder("layout(std140) uniform " + blockName + " {\n");
		int at = 0;
		for (var e : types.entrySet()) {
			int[] sa = std140(e.getValue());
			at = (at + sa[1] - 1) / sa[1] * sa[1];
			offsets.put(e.getKey(), at);
			at += sa[0];
			block.append("    ").append(e.getValue()).append(' ').append(e.getKey()).append(";\n");
		}
		block.append("};\n");
		int size = (at + 15) & ~15;
		return new Loose(insertBlock(LOOSE.matcher(vertex).replaceAll(""), block.toString()),
			insertBlock(LOOSE.matcher(fragment).replaceAll(""), block.toString()), offsets, size);
	}

	/** {size, base alignment} in std140. */
	private static int[] std140(String type) {
		return switch (type) {
			case "float", "int", "uint", "bool" -> new int[] {4, 4};
			case "vec2", "ivec2", "uvec2" -> new int[] {8, 8};
			case "vec3", "ivec3", "uvec3" -> new int[] {12, 16};
			case "vec4", "ivec4", "uvec4" -> new int[] {16, 16};
			case "mat3" -> new int[] {48, 16};
			default -> new int[] {64, 16};
		};
	}

	private static String insertBlock(String src, String block) {
		Matcher v = Pattern.compile("(?m)^#version[^\\n]*\\n").matcher(src);
		if (v.find()) return src.substring(0, v.end()) + block + src.substring(v.end());
		return block + src;
	}

	/** Interface locations an object of this type uses (matrices take a column each, arrays an element each). */
	private static int slots(String type, String array) {
		int per = switch (type) {
			case "mat2", "dmat2" -> 2;
			case "mat3", "dmat3" -> 3;
			case "mat4", "dmat4" -> 4;
			default -> 1;
		};
		if (array.isEmpty()) return per;
		String n = array.substring(1, array.length() - 1).trim();
		try {
			return per * Integer.parseInt(n);
		} catch (NumberFormatException e) {
			return per * 8; // a constant expression: leave generous room
		}
	}

	/** GL's value for an attribute with no array enabled: (0, 0, 0, 1). */
	private static String defaultValue(String type) {
		return switch (type) {
			case "float" -> "0.0";
			case "vec2" -> "vec2(0.0)";
			case "vec3" -> "vec3(0.0)";
			case "vec4" -> "vec4(0.0, 0.0, 0.0, 1.0)";
			case "int" -> "0";
			case "ivec2" -> "ivec2(0)";
			case "ivec3" -> "ivec3(0)";
			case "ivec4" -> "ivec4(0, 0, 0, 1)";
			case "uint" -> "0u";
			case "uvec2" -> "uvec2(0u)";
			case "uvec3" -> "uvec3(0u)";
			case "uvec4" -> "uvec4(0u, 0u, 0u, 1u)";
			default -> type + "(0)";
		};
	}

	/** Splits combined GLSL into the part before #version handling is irrelevant here; kept for symmetry with injectDefines. */
	static List<String> names(Map<String, Integer> varyings) {
		return new ArrayList<>(varyings.keySet());
	}
}
