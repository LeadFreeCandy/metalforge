package io.github.leadfreecandy.metalforge.metal;

/**
 * Smooth frame pacing (MetalForge). Uncapped, frames take 2-4 ms with a 10-20 ms one on every client tick (20/s), and the
 * display shows whichever frame finished last before each refresh: the time each shown frame was sampled at jumps around,
 * so panning judders even at hundreds of fps, and the drop-to-the-last-frame pacer (mc_pace) misses refreshes outright when
 * the frame it waited for turns out to be a tick frame.
 * <p>
 * Here frames start on a fixed beat of one refresh period (or a whole multiple of it when the game's frame limit is lower),
 * and each is presented at a scheduled refresh LEAD after it started (presentDrawable:atTime:). Frames sampled one period
 * apart are shown one period apart, and the slack between a frame's cost and the period absorbs the tick frames. LEAD is the
 * frame's start-to-GPU-done time at the 97th percentile plus a margin for the compositor's latch, at least one period;
 * it is re-chosen every 2 s so the target only shifts rarely.
 * <p>
 * On when the game's VSync option is on (-Dmetalforge.metal.smooth=vsync, the default), always (=on) or never (=off).
 */
final class MetalPacer {
	private static final String MODE = System.getProperty("metalforge.metal.smooth", "vsync");
	private static final double LATCH_S = Double.parseDouble(System.getProperty("metalforge.metal.smoothLatchMs", "2.5")) / 1000;
	private static final int WINDOW = 256;
	/** Most lead allowed, in refresh periods (latency vs. how slow a frame can be and still make its refresh). */
	private static final double MAX_LEAD = Double.parseDouble(System.getProperty("metalforge.metal.smoothMaxLead", "3"));

	private final double[] costs = new double[WINDOW];
	private int costCount, costNext;
	private double start;        // this frame's scheduled start (0 = not pacing yet)
	private double lead;         // start -> display
	private double leadChosenAt;
	private double lastPeriod;

	static boolean enabled(boolean vsync) {
		return switch (MODE) {
			case "on" -> true;
			case "off" -> false;
			default -> vsync;
		};
	}

	/** The beat: the refresh period, times n when the game's frame limit asks for fewer frames than the display shows. */
	private static double beat(double period) {
		int limit = net.minecraft.client.Minecraft.getInstance().getFramerateLimitTracker().getFramerateLimit();
		if (limit <= 0 || limit >= 260) return period;
		long n = Math.max(1, Math.round(1.0 / (period * limit)));
		return period * n;
	}

	/** Before this frame's present: the refresh it should be shown at (0 = as soon as possible). */
	double target() {
		double period = Native.refreshPeriod(), anchor = Native.nextRefresh();
		if (period <= 0 || this.start <= 0) return 0;
		double now = Native.now();
		if (now - anchor > 0.25) return 0; // no refreshes lately (display asleep)
		// the first refresh at least `lead` after the frame's scheduled start
		return anchor + Math.ceil((this.start + this.lead - anchor) / period - 1e-6) * period;
	}

	/** After the frame is submitted: wait for the next frame's start. */
	void endFrame() {
		Native.paceStart();
		double period = Native.refreshPeriod();
		double now = Native.now();
		if (period <= 0) {
			this.start = 0;
			return;
		}
		if (this.start <= 0 || Math.abs(period - this.lastPeriod) > 1e-4) {
			this.lastPeriod = period;
			this.start = now;
			this.actualStart = now;
			this.lead = period * 1.5;
			this.leadChosenAt = now;
			return;
		}
		// this frame's cost: scheduled start to submit, plus the GPU's (smoothed) encode-to-done time
		this.costs[this.costNext] = now - this.start + Native.gpuLatency();
		this.costNext = (this.costNext + 1) % WINDOW;
		if (this.costCount < WINDOW) this.costCount++;
		if (now - this.leadChosenAt > 2 && this.costCount >= 60) {
			double[] c = java.util.Arrays.copyOf(this.costs, this.costCount);
			java.util.Arrays.sort(c);
			double p97 = c[(int) (c.length * 0.97)];
			this.lead = Math.min(MAX_LEAD * period, Math.max(period, p97 + LATCH_S));
			this.leadChosenAt = now;
		}
		// the frame's own work (from when it actually started), for deciding whether a late frame can still catch up
		double work = now - this.actualStart + Native.gpuLatency();
		this.workAvg += (work - this.workAvg) * 0.05;
		double beat = beat(period);
		double next = this.start + beat;
		// Overran the beat (a tick frame): the next frame starts right away and keeps its scheduled refresh as long as it can
		// still make it (frames after a slow one are short, so the schedule catches up). Only if it can't is a beat skipped:
		// skipping is what shows a refresh twice.
		if (now > next) {
			this.late++;
			if (now + this.workAvg > next + this.lead - LATCH_S) {
				next = this.start + Math.ceil((now + this.workAvg - this.lead + LATCH_S - this.start) / beat) * beat;
				this.skippedBeats++;
			}
		}
		// never wait more than three beats (or 100 ms): a bad refresh reading must not freeze the game
		long wait = Math.min((long) ((next - now) * 1e9), (long) (Math.min(3 * beat, 0.1) * 1e9));
		if (wait > 0) Native.sleepPrecise(wait);
		if (next - now > 3 * beat) next = Native.now();
		this.start = next;
		this.actualStart = Native.now();
		this.frames++;
		if (now - this.loggedAt > 10) {
			if (this.loggedAt > 0) org.slf4j.LoggerFactory.getLogger("MetalForge").info(
				"smooth pacing: {} Hz, beat {} ms, lead {} ms, work avg {} ms, {} frames, {} started late, {} beats skipped",
				Math.round(1 / period), String.format("%.2f", beat * 1e3), String.format("%.2f", this.lead * 1e3),
				String.format("%.2f", this.workAvg * 1e3), this.frames, this.late, this.skippedBeats);
			this.loggedAt = now;
			this.frames = this.late = this.skippedBeats = 0;
		}
	}

	private double actualStart, workAvg, loggedAt;
	private int frames, late, skippedBeats;

	void reset() {
		this.start = 0;
		this.costCount = 0;
	}
}
