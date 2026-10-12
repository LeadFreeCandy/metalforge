package io.github.leadfreecandy.metalforge.metal;

/** Frame statistics the test harness reads (reflectively): GPU time of completed frames, presents, skipped presents. */
public final class MetalStats {
	static double gpuMicros;
	static long gpuFrames, presents, skipped, encoders, submits;

	private MetalStats() {
	}

	public static void reset() {
		gpuMicros = 0;
		gpuFrames = presents = skipped = encoders = submits = 0;
	}

	/** {gpu ms per frame, frames timed, presents, presents skipped by pacing, render encoders per frame} since the last reset. */
	public static double[] snapshot() {
		return new double[] {gpuFrames == 0 ? 0 : gpuMicros / gpuFrames / 1000.0, gpuFrames, presents, skipped, submits == 0 ? 0 : (double) encoders / submits};
	}
}
