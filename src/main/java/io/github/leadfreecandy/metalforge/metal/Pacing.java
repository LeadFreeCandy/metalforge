package io.github.leadfreecandy.metalforge.metal;

/** Public view of the backend's pacing state for mixins. */
public final class Pacing {
	private Pacing() {
	}

	public static boolean smooth() {
		return MetalDevice.smoothPacing();
	}
}
