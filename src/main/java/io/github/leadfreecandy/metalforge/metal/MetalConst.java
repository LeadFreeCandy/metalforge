// Ported from mcopt (https://github.com/noahdunnagan/mcopt), Apache-2.0, Copyright 2026 Noah Dunnagan; re-targeted from 26.3's
// renderpearl enums to 26.1.2's Blaze3D ones.
package io.github.leadfreecandy.metalforge.metal;

import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

/** Blaze3D enums to Metal enum values (see the Metal headers for the numbers). */
final class MetalConst {
	/** Buffer argument table layout shared by every translated shader: uniforms from 0, vertex buffers from 27. */
	static final int VERTEX_BUFFER_BASE = 27;
	static final int MAX_SLOTS = 26;

	static final int BGRA8 = 80, D32F = 252, D32F_S8 = 260;

	private MetalConst() {
	}

	static int pixelFormat(TextureFormat format) {
		return switch (format) {
			case RGBA8 -> 70;          // RGBA8Unorm
			case RED8 -> 10;           // R8Unorm
			case RED8I -> 14;          // R8Sint
			case DEPTH32 -> D32F;      // Depth32Float
			case DEPTH24_STENCIL8, DEPTH32_STENCIL8 -> D32F_S8; // Apple GPUs have no 24-bit depth
			default -> throw new IllegalArgumentException("Metal has no texture format for " + format);
		};
	}

	/** MTLVertexFormat for a vertex format element, following GlStateManager's rules (int unless normalized or float). */
	static int vertexFormat(VertexFormatElement e) {
		boolean norm = e.normalized();
		int n = e.count();
		return switch (e.type()) {
			case FLOAT -> switch (n) { case 1 -> 28; case 2 -> 29; case 3 -> 30; default -> 31; };
			case UBYTE -> norm ? switch (n) { case 1 -> 47; case 2 -> 7; case 3 -> 8; default -> 9; }
				: switch (n) { case 1 -> 45; case 2 -> 1; case 3 -> 2; default -> 3; };
			case BYTE -> norm ? switch (n) { case 1 -> 48; case 2 -> 10; case 3 -> 11; default -> 12; }
				: switch (n) { case 1 -> 46; case 2 -> 4; case 3 -> 5; default -> 6; };
			case USHORT -> norm ? switch (n) { case 1 -> 51; case 2 -> 19; case 3 -> 20; default -> 21; }
				: switch (n) { case 1 -> 49; case 2 -> 13; case 3 -> 14; default -> 15; };
			case SHORT -> norm ? switch (n) { case 1 -> 52; case 2 -> 22; case 3 -> 23; default -> 24; }
				: switch (n) { case 1 -> 50; case 2 -> 16; case 3 -> 17; default -> 18; };
			case UINT -> switch (n) { case 1 -> 36; case 2 -> 37; case 3 -> 38; default -> 39; };
			case INT -> switch (n) { case 1 -> 32; case 2 -> 33; case 3 -> 34; default -> 35; };
		};
	}

	static int compare(CompareOp op) {
		return switch (op) {
			case NEVER_PASS -> 0;
			case LESS_THAN -> 1;
			case EQUAL -> 2;
			case LESS_THAN_OR_EQUAL -> 3;
			case GREATER_THAN -> 4;
			case NOT_EQUAL -> 5;
			case GREATER_THAN_OR_EQUAL -> 6;
			case ALWAYS_PASS -> 7;
		};
	}

	static int blendFactor(SourceFactor f) {
		return switch (f) {
			case ZERO -> 0;
			case ONE -> 1;
			case SRC_COLOR -> 2;
			case ONE_MINUS_SRC_COLOR -> 3;
			case SRC_ALPHA -> 4;
			case ONE_MINUS_SRC_ALPHA -> 5;
			case DST_COLOR -> 6;
			case ONE_MINUS_DST_COLOR -> 7;
			case DST_ALPHA -> 8;
			case ONE_MINUS_DST_ALPHA -> 9;
			case SRC_ALPHA_SATURATE -> 10;
			case CONSTANT_COLOR -> 11;
			case ONE_MINUS_CONSTANT_COLOR -> 12;
			case CONSTANT_ALPHA -> 13;
			case ONE_MINUS_CONSTANT_ALPHA -> 14;
		};
	}

	static int blendFactor(DestFactor f) {
		return switch (f) {
			case ZERO -> 0;
			case ONE -> 1;
			case SRC_COLOR -> 2;
			case ONE_MINUS_SRC_COLOR -> 3;
			case SRC_ALPHA -> 4;
			case ONE_MINUS_SRC_ALPHA -> 5;
			case DST_COLOR -> 6;
			case ONE_MINUS_DST_COLOR -> 7;
			case DST_ALPHA -> 8;
			case ONE_MINUS_DST_ALPHA -> 9;
			case CONSTANT_COLOR -> 11;
			case ONE_MINUS_CONSTANT_COLOR -> 12;
			case CONSTANT_ALPHA -> 13;
			case ONE_MINUS_CONSTANT_ALPHA -> 14;
		};
	}

	/** MTLPrimitiveType, as GlConst maps the modes (QUADS/LINES arrive as indexed triangles); 5 marks a fan, re-indexed natively. */
	static int primitive(VertexFormat.Mode mode) {
		return switch (mode) {
			case POINTS -> 0;
			case DEBUG_LINES -> 1;
			case DEBUG_LINE_STRIP -> 2;
			case LINES, TRIANGLES, QUADS -> 3;
			case TRIANGLE_STRIP -> 4;
			case TRIANGLE_FAN -> 5;
		};
	}

	/** MTLPrimitiveTopologyClass: point 1, line 2, triangle 3. */
	static int topologyClass(VertexFormat.Mode mode) {
		return switch (primitive(mode)) {
			case 0 -> 1;
			case 1, 2 -> 2;
			default -> 3;
		};
	}

	static int addressMode(AddressMode mode) {
		return switch (mode) {
			case CLAMP_TO_EDGE -> 0;
			case REPEAT -> 2;
		};
	}

	static int filter(FilterMode mode) {
		return mode == FilterMode.LINEAR ? 1 : 0;
	}
}
