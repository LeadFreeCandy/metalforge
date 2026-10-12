// Ported from mcopt (https://github.com/noahdunnagan/mcopt), Apache-2.0, Copyright 2026 Noah Dunnagan: late drawable acquire and
// present pacing. The layer comes from the GLFW window's NSView here (26.3 uses SDL).
package io.github.leadfreecandy.metalforge.metal;

import org.lwjgl.glfw.GLFWNativeCocoa;

/**
 * A CAMetalLayer on the window's content view. The drawable is fetched at present time rather than frame start: holding it
 * for the whole frame only adds latency and can stall on a drawable the display hasn't released yet.
 */
final class MetalSurface {
	/** With vsync off, present only the last frame that makes each refresh (mc_pace); -Dmetalforge.metal.pace=false presents every frame. */
	private static final boolean PACE = Boolean.parseBoolean(System.getProperty("metalforge.metal.pace", "true"));
	/** How long before a refresh a paced present must be done (the compositor's latch). */
	private static final double PACE_MARGIN_S = Double.parseDouble(System.getProperty("metalforge.metal.paceMarginMs", "2")) / 1000;
	/** The layer's maximumDrawableCount (2 or 3; default 3). */
	private static final int DRAWABLES = Integer.getInteger("metalforge.metal.drawables", 0);
	private static final boolean PACE_ADAPT = Boolean.parseBoolean(System.getProperty("metalforge.metal.paceAdapt", "true"));

	private final long ctx;
	private final long view;
	final long layer;
	private int width, height;
	private boolean vsync, configured;
	private long presented, skipped;

	MetalSurface(long ctx, long window) {
		this.ctx = ctx;
		this.view = GLFWNativeCocoa.glfwGetCocoaView(window);
		if (this.view == 0) throw new IllegalStateException("no NSView for the GLFW window");
		this.layer = Native.layerAttach(this.view);
	}

	void setVsync(boolean vsync) {
		this.vsync = vsync;
		this.configured = false;
		this.smooth = MetalPacer.enabled(vsync);
		this.pacer.reset();
	}

	/** Smooth pacing (MetalPacer): every frame is presented, at a scheduled refresh, and the next frame waits for its beat. */
	private boolean smooth = MetalPacer.enabled(false);
	final MetalPacer pacer = new MetalPacer();

	boolean smooth() {
		return this.smooth;
	}

	private int frames;

	/**
	 * -Dmetalforge.metal.presentQueue=acquire: mcopt's off-thread present (noahdunnagan/mcopt#2): the frame only copies its image
	 * into a staging slot; a second queue acquires the drawable and presents, so the render thread never waits in nextDrawable.
	 */
	static final boolean PRESENT_QUEUE = "acquire".equals(System.getProperty("metalforge.metal.presentQueue", "acquire"));

	/** Configures the layer if needed and asks the pacer whether this frame should be presented (vsync off). */
	boolean shouldPresent(int width, int height) {
		if ((this.frames++ & 63) == 0) Native.paceDisplay(Native.viewDisplay(this.view));
		if (!this.configured || width != this.width || height != this.height) {
			this.width = width;
			this.height = height;
			boolean paced = !this.vsync && !this.smooth && PACE;
			if (PACE_ADAPT) Native.paceAdapt(paced ? 1 : 0);
			Native.layerScale(this.layer, this.view);
			Native.layerConfigure(this.ctx, this.layer, width, height, (this.vsync || this.smooth ? 1 : 0) | DRAWABLES << 8);
			this.configured = true;
		}
		return this.vsync || this.smooth || !PACE || Native.pace(PACE_MARGIN_S);
	}

	/** The drawable to present this frame into (retained), or 0 to skip presenting it. */
	long acquire(int width, int height) {
		if (!this.shouldPresent(width, height)) return 0;
		this.presented++;
		return Native.layerNext(this.layer);
	}

	long presented() {
		return this.presented;
	}

	long skipped() {
		return this.skipped;
	}
}
