package com.doomsday.nukes.util;

/**
 * Version-agnostic, allocation-free math used by both the server simulation and the
 * client renderers. Deliberately Minecraft-free so it can be unit tested
 * (see {@code src/test/java}) without booting the game.
 *
 * <p>All curve helpers are written so that a parameter of {@code 0} degrades to a
 * well-defined value rather than NaN/∞ — an effect parameter arriving from a config
 * file must never be able to produce {@code NaN} positions in a vertex buffer.</p>
 */
public final class MathUtil {
	public static final double EPS = 1.0E-6D;
	public static final float PI_F = (float) Math.PI;
	public static final float TWO_PI_F = (float) (Math.PI * 2.0D);

	private MathUtil() {
	}

	// ——————————————————————————————————————————— scalar helpers

	public static float clamp(float v, float lo, float hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	public static double clamp(double v, double lo, double hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	public static int clamp(int v, int lo, int hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	public static double clamp01(double v) {
		return clamp(v, 0.0D, 1.0D);
	}

	public static float clamp01(float v) {
		return clamp(v, 0.0F, 1.0F);
	}

	public static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	public static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	public static float mix(float a, float b, float t) {
		return a * (1.0F - t) + b * t;
	}

	/** Classic Hermite smoothstep; flat at both ends, which is what film-style fades want. */
	public static float smoothstep(float edge0, float edge1, float x) {
		if (Math.abs(edge1 - edge0) < EPS) {
			return x < edge0 ? 0.0F : 1.0F;
		}
		float t = clamp01((x - edge0) / (edge1 - edge0));
		return t * t * (3.0F - 2.0F * t);
	}


	/**
	 * Physically-inspired falloff used for flash, knockback and sound attenuation.
	 *
	 * <p>{@code ref} is a "reference distance" that removes the singularity at the
	 * origin, so the curve saturates to 1.0 inside {@code ref} instead of diverging,
	 * and {@code max} gives a configurable, <em>soft</em> outer limit rather than a
	 * binary cutoff. {@code exponent} selects inverse-linear (1), inverse-square (2,
	 * physically correct) or a steeper artistic falloff.</p>
	 */
	public static double attenuation(double distance, double ref, double max, double exponent) {
		double d = Math.max(0.0D, distance);
		double r = Math.max(EPS, ref);
		double m = Math.max(r + EPS, max);
		// Inverse-square-like core, normalised so f(0)=1, then a smooth window to zero.
		double core = (r * r) / (r * r + d * d);
		if (Math.abs(exponent - 2.0D) > EPS) {
			core = Math.pow(core, clamp(exponent, 0.25D, 6.0D) * 0.5D);
		}
		double window = 1.0D - smoothstep((float) r, (float) m, (float) d);
		return clamp01(core * (0.15D + 0.85D * window));
	}

	/** Convenience: 1 - t shaped like an exponential decay with a floor. */
	public static double decay(double t, double rate) {
		return Math.exp(-Math.max(0.0D, t) * Math.max(0.0D, rate));
	}

	/**
	 * Time-normalised "blast" envelope: fast attack, long tail. Used for exposure,
	 * cloud boil and shake amplitude so nothing snaps or pops.
	 */
	public static float blastEnvelope(float t01, float attack, float decay) {
		float t = clamp01(t01);
		float a = Math.max(0.001F, attack);
		float d = Math.max(0.01F, decay);
		float up = t < a ? t / a : 1.0F;
		// The floored decay, not the raw parameter: `decay = 0` (which the config path allows, and
		// which an envelope with no tail *means*) divided 0 by 0 and returned NaN into every alpha
		// and shake amplitude downstream — and a NaN in a vertex buffer is an invisible, permanent
		// bug. With the floor, a zero tail is simply "no decay", which is what the caller asked for.
		float down = d / (d + Math.max(0.0F, t - a) * 4.0F);
		return clamp01(up * up * down);
	}

	/**
	 * Blackbody approximation (Tanner Helland's fit, normalised to 0..1).
	 * Temperature in Kelvin; drives fireball colour from white-hot to deep red.
	 */
	public static float[] blackbody(float kelvin) {
		float t = clamp(kelvin, 1000.0F, 40000.0F) / 100.0F;
		float r;
		float g;
		float b;
		if (t <= 66.0F) {
			r = 255.0F;
			g = 99.4708025861F * (float) Math.log(t) - 161.1195681661F;
			b = t <= 19.0F ? 0.0F : 138.5177312231F * (float) Math.log(t - 10.0F) - 305.0447927307F;
		} else {
			r = 329.698727446F * (float) Math.pow(t - 60.0F, -0.1332047592F);
			g = 288.1221695283F * (float) Math.pow(t - 60.0F, -0.0755148492F);
			b = 255.0F;
		}
		return new float[]{clamp(r, 0.0F, 255.0F) / 255.0F, clamp(g, 0.0F, 255.0F) / 255.0F,
			clamp(b, 0.0F, 255.0F) / 255.0F};
	}

	// —————————————————————————————————————————— cheap hashing

	/** Deterministic 32-bit integer hash (used for per-instance random seeds). */
	public static int hash(int x, int y, int z) {
		int h = x * 374761393 + y * 668265263 + z * 2147483647;
		h = (h ^ (h >>> 13)) * 1274126177;
		return h ^ (h >>> 16);
	}

	public static int hash(long seed, int index) {
		long h = seed * 0x9E3779B97F4A7C15L + index * 0xC2B2AE3D27D4EB4FL;
		h ^= h >>> 33;
		h *= 0xff51afd7ed558ccdL;
		h ^= h >>> 33;
		return (int) h;
	}

	/** Deterministic float in [0,1) from an int hash — replaces java.util.Random on hot paths. */
	public static float hash01(int h) {
		return ((h >>> 8) & 0xFFFFFF) / (float) 0x1000000;
	}

	public static float signed01(int h) {
		return hash01(h) * 2.0F - 1.0F;
	}

	/** Cheap xorshift generator used by pooled particle spawns (no allocation). */
	public static int nextInt(int state) {
		int x = state == 0 ? 0x6C078965 : state;
		x ^= x << 13;
		x ^= x >>> 17;
		x ^= x << 5;
		return x;
	}
}
