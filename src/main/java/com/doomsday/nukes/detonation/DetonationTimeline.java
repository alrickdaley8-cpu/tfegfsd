package com.doomsday.nukes.detonation;

import com.doomsday.nukes.config.DoomsdayConfig;
import com.doomsday.nukes.util.MathUtil;

/**
 * Immutable stage schedule for one detonation: every start/length pair in seconds,
 * relative to {@code T0} (the detonation tick), derived purely from the resolved
 * tuning + config.
 *
 * <p><b>Sync model.</b> The server computes a timeline and <em>sends it</em> inside the
 * detonation packet (as absolute world-time stamps). Clients never re-derive timing
 * from their own config — otherwise a client with a different {@code cloudLifetime}
 * would fade its cloud out of sync with the server's contamination. Clients use their
 * local config only for <em>quality</em> (particle counts, LOD thresholds), never for
 * <em>when</em>. This is what makes the effect reconstructible from one compact packet
 * and immune to drift over a 90-second cloud.</p>
 *
 * <p>Minecraft-free on purpose: see {@code DetonationTimelineTest}.</p>
 */
public final class DetonationTimeline {
	/** Seconds per tick. */
	public static final float TPS = 20.0F;

	// stage windows, in seconds from T0
	public final float flashStart;
	public final float flashLength;
	public final float fireballStart;
	public final float fireballGrow;
	public final float fireballLength;
	public final float shockwaveStart;
	public final float shockwaveLength;
	public final float cloudStart;
	public final float cloudLength;
	public final float falloutStart;
	public final float falloutLength;
	public final float aftermathStart;
	public final float aftermathLength;
	public final float totalLength;

	/** Destructive wave front kinematics, mirrored by the renderer for the dome. */
	public final double waveSpeedBlocksPerSecond;
	public final double waveMaxRadius;
	/** Cloud kinematics used by both the shadow estimate and the renderer. */
	public final double cloudRisePerSecond;

	private final float[] stageStarts;

	public DetonationTimeline(DoomsdayConfig c, ConfigManager.ResolvedTuning tuning) {
		double timeScale = Math.max(0.15D, tuning.yieldKt() >= 0.05D
			? NukePreset.timeScaleFor(tuning.yieldKt()) : 1.0D);

		this.flashStart = 0.0F;
		// Phase A (whiteout) then phase B (desaturation/restore): 0.20 + ~2.8 = ~3.0 s
		this.flashLength = Math.max(0.35F, c.flashWhiteoutSeconds + c.flashDesaturateSeconds);

		this.fireballStart = 0.08F;
		this.fireballGrow = Math.max(0.1F, c.fireballGrowSeconds * (float) MathUtil.clamp(timeScale * 0.5D + 0.5D, 0.5F, 2.2F));
		this.fireballLength = this.fireballGrow
			+ Math.max(0.0F, c.fireballHoldSeconds)
			+ Math.max(0.1F, c.fireballFadeSeconds);

		this.shockwaveStart = 0.30F;
		this.shockwaveLength = Math.max(0.4F, c.shockwaveDurationSeconds);
		this.waveSpeedBlocksPerSecond = Math.max(1.0D, c.shockwaveSpeed);
		this.waveMaxRadius = Math.max(8.0D,
			Math.max(tuning.shockwaveRadius(), tuning.blastRadius() * 2.4D));

		this.cloudStart = Math.min(2.0F, this.fireballStart + this.fireballGrow * 0.55F);
		this.cloudLength = Math.max(2.0F, c.cloudLifetimeSeconds * (float) MathUtil.clamp(timeScale, 0.6D, 2.4D));
		this.cloudRisePerSecond = Math.max(0.0D, c.cloudRiseBlocks / (double) this.cloudLength);

		this.falloutStart = this.cloudStart + 0.6F;
		this.falloutLength = Math.max(2.0F, c.ashFallSeconds * (float) MathUtil.clamp(timeScale, 0.8D, 2.0D));

		this.aftermathStart = Math.max(this.shockwaveStart + this.shockwaveLength, this.cloudStart + 2.0F);
		this.aftermathLength = Math.max(4.0F, c.atmosphereRecoverSeconds * (float) MathUtil.clamp(timeScale, 0.7D, 1.6D));

		this.totalLength = max(
			this.flashStart + this.flashLength,
			this.fireballStart + this.fireballLength,
			this.shockwaveStart + this.shockwaveLength,
			this.cloudStart + this.cloudLength,
			this.falloutStart + this.falloutLength,
			this.aftermathStart + this.aftermathLength,
			6.0F);

		// Start stamps in stage order, used by both driver and renderer for O(1) lookup.
		this.stageStarts = new float[]{
			0.0F,
			0.0F,
			this.flashStart,
			this.fireballStart,
			this.shockwaveStart,
			this.cloudStart,
			this.falloutStart,
			this.aftermathStart,
			this.totalLength
		};
	}

	private static float max(float... vs) {
		float m = 0.0F;
		for (float v : vs) {
			m = Math.max(m, v);
		}
		return m;
	}

	/** @return seconds since T0, clamped to the timeline (never negative). */
	public float timeAt(long startWorldTime, long currentWorldTime) {
		long deltaTicks = currentWorldTime - startWorldTime;
		if (deltaTicks <= 0L) {
			return 0.0F;
		}
		return Math.min(totalLength + 1.0F, deltaTicks / TPS);
	}

	/** Stage active at time {@code t}; monotonic — never returns an earlier stage. */
	public DetonationStage stageAt(float t) {
		if (t >= totalLength) {
			return DetonationStage.COMPLETE;
		}
		DetonationStage result = DetonationStage.FLASH;
		for (int i = DetonationStage.FLASH.index(); i <= DetonationStage.AFTERMATH.index(); i++) {
			if (t >= stageStarts[i]) {
				result = DetonationStage.byIndex(i);
			}
		}
		return result;
	}

	public float startOf(DetonationStage stage) {
		int i = stage.index();
		return i >= 0 && i < stageStarts.length ? stageStarts[i] : totalLength;
	}

	/** Normalised 0..1 progress through {@code stage} at time {@code t}. */
	public float progress(DetonationStage stage, float t) {
		float s = startOf(stage);
		float len = lengthOf(stage);
		if (len <= 1.0E-4F) {
			return t >= s ? 1.0F : 0.0F;
		}
		return MathUtil.clamp01((t - s) / len);
	}

	public float lengthOf(DetonationStage stage) {
		switch (stage) {
			case FLASH:
				return flashLength;
			case FIREBALL:
				return fireballLength;
			case SHOCKWAVE:
				return shockwaveLength;
			case MUSHROOM_CLOUD:
				return cloudLength;
			case FALLOUT:
				return falloutLength;
			case AFTERMATH:
				return aftermathLength;
			case COMPLETE:
				return 0.0F;
			default:
				return totalLength;
		}
	}

	/**
	 * Radius of the destructive/visible wavefront at time {@code t}, in blocks.
	 * Uses a decelerating front: real blast waves slow with distance, and the
	 * deceleration also bounds how quickly the server must finish its work.
	 */
	public double waveRadiusAt(float t) {
		float local = Math.max(0.0F, t - shockwaveStart);
		double k = waveSpeedBlocksPerSecond * (local / Math.max(0.05F, shockwaveLength));
		// Exponential approach to waveMaxRadius (never overshoots, never NaN).
		return waveMaxRadius * (1.0D - MathUtil.decay(local, k / Math.max(1.0D, waveMaxRadius)));
	}

	/** 0..1 intensity envelope of the visible condensation dome. */
	public float domeEnvelope(float t) {
		float p = progress(DetonationStage.SHOCKWAVE, t);
		return MathUtil.blastEnvelope(p, 0.06F, 1.0F);
	}

	/** Fireball radius in blocks at {@code t}; grows to the tuned maximum then dies. */
	public double fireballRadiusAt(float t, double maxRadius) {
		float local = t - fireballStart;
		if (local <= 0.0F) {
			return 0.0D;
		}
		float grow = Math.min(1.0F, local / Math.max(0.05F, fireballGrow));
		float base = (float) MathUtil.smoothstep(0.0F, 1.0F, grow);
		// buoyant overshoot: fireballs briefly overshoot then slump — the classic look
		float overshoot = 1.0F + 0.13F * (float) Math.sin(grow * Math.PI);
		float fade = local <= fireballGrow
			? 1.0F
			: MathUtil.clamp01(1.0F - (local - fireballGrow) / Math.max(0.2F, fireballLength - fireballGrow));
		return Math.max(0.0D, maxRadius * base * overshoot * (0.35F + 0.65F * fade));
	}

	public float totalLength() {
		return totalLength;
	}

	@Override
	public String toString() {
		return String.format(java.util.Locale.ROOT,
			"Timeline{flash=%.2f/%.2f fire=%.2f/%.2f wave=%.2f/%.2f cloud=%.2f/%.2f "
				+ "fallout=%.2f/%.2f after=%.2f/%.2f total=%.2f}",
			flashStart, flashLength, fireballStart, fireballLength, shockwaveStart, shockwaveLength,
			cloudStart, cloudLength, falloutStart, falloutLength, aftermathStart, aftermathLength,
			totalLength);
	}
}
