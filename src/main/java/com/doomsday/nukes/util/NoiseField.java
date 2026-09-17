package com.doomsday.nukes.util;

/**
 * Tiling 3D value/simplex-style noise + derived fields (fbm, curl) shared by the
 * fireball, shockwave and mushroom-cloud renderers, and mirrored line-for-line in
 * {@code assets/doomsday/shaders/program/*.glsl} so CPU motion and GPU motion agree.
 *
 * <p>Cost is bounded and allocation-free: one gradient lattice look-up per call, no
 * temporaries, no {@code double}. Renderers call this at most a few thousand times
 * per frame for cloud segments (see {@code MushroomCloudRenderer} LOD), and the
 * per-vertex turbulence is done on the GPU.</p>
 */
public final class NoiseField {
	/** Guard for normalising fbm sums whose amplitude can round to zero. */
	private static final float EPS = 1.0E-6F;

	/** Lattice period. Power-of-two so masking replaces a modulo and tiles cleanly. */
	private static final int PERIOD = 256;
	private static final int MASK = PERIOD - 1;

	private static final int[] P = buildPermutation(1618033988L);

	private NoiseField() {
	}

	private static int[] buildPermutation(long seed) {
		int[] perm = new int[PERIOD];
		for (int i = 0; i < PERIOD; i++) {
			perm[i] = i;
		}
		// Deterministic Fisher-Yates with xorshift64* — same result on every client and
		// on the server, which keeps client visuals in lock-step with server timing.
		int s = (int) (seed ^ (seed >>> 32));
		for (int i = PERIOD - 1; i > 0; i--) {
			s = MathUtil.nextInt(s);
			int j = Math.abs(s) % (i + 1);
			int tmp = perm[i];
			perm[i] = perm[j];
			perm[j] = tmp;
		}
		return perm;
	}

	private static float fade(float t) {
		return t * t * t * (t * (t * 6.0F - 15.0F) + 10.0F);
	}

	private static float grad(int hash, float x, float y, float z) {
		int h = hash & 15;
		float u = h < 8 ? x : y;
		float v = h < 4 ? y : (h == 12 || h == 14 ? x : z);
		return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
	}

	/** Classic Perlin gradient noise, roughly in [-1, 1]. */
	public static float perlin(float x, float y, float z) {
		int xi = (int) Math.floor(x);
		int yi = (int) Math.floor(y);
		int zi = (int) Math.floor(z);
		float xf = x - xi;
		float yf = y - yi;
		float zf = z - zi;

		// Lattice wrap: masking keeps every index inside the permutation table, which also
		// makes the field tile with period 256 (used by the looping cloud noise).
		int x0 = xi & MASK;
		int y0 = yi & MASK;
		int z0 = zi & MASK;

		float u = fade(xf);
		float v = fade(yf);
		float w = fade(zf);

		int a = P[x0] + y0;
		int aa = P[a & MASK] + z0;
		int ab = P[(a + 1) & MASK] + z0;
		int b = P[(x0 + 1) & MASK] + y0;
		int ba = P[b & MASK] + z0;
		int bb = P[(b + 1) & MASK] + z0;

		float g000 = grad(P[aa & MASK], xf, yf, zf);
		float g100 = grad(P[ba & MASK], xf - 1.0F, yf, zf);
		float g010 = grad(P[ab & MASK], xf, yf - 1.0F, zf);
		float g110 = grad(P[bb & MASK], xf - 1.0F, yf - 1.0F, zf);
		float g001 = grad(P[(aa + 1) & MASK], xf, yf, zf - 1.0F);
		float g101 = grad(P[(ba + 1) & MASK], xf - 1.0F, yf, zf - 1.0F);
		float g011 = grad(P[(ab + 1) & MASK], xf, yf - 1.0F, zf - 1.0F);
		float g111 = grad(P[(bb + 1) & MASK], xf - 1.0F, yf - 1.0F, zf - 1.0F);

		float x00 = MathUtil.mix(g000, g100, u);
		float x10 = MathUtil.mix(g010, g110, u);
		float x01 = MathUtil.mix(g001, g101, u);
		float x11 = MathUtil.mix(g011, g111, u);
		float y0f = MathUtil.mix(x00, x10, v);
		float y1f = MathUtil.mix(x01, x11, v);
		return MathUtil.mix(y0f, y1f, w);
	}

	/** Fractal Brownian motion with a fixed octave count (bounded cost). */
	public static float fbm(float x, float y, float z, int octaves) {
		float sum = 0.0F;
		float amp = 0.5F;
		float norm = 0.0F;
		float fx = x;
		float fy = y;
		float fz = z;
		int n = MathUtil.clamp(octaves, 1, 6);
		for (int i = 0; i < n; i++) {
			sum += perlin(fx, fy, fz) * amp;
			norm += amp;
			fx *= 2.02F;
			fy *= 2.03F;
			fz *= 2.01F;
			amp *= 0.5F;
		}
		return norm > EPS ? sum / norm : 0.0F;
	}

	/**
	 * Divergence-free curl of an fbm potential, sampled by central differences.
	 * This is what gives the cloud its rolling, non-self-annihilating motion.
	 * Written into {@code out} (length 3) to avoid allocating on the render path.
	 */
	public static void curl(float x, float y, float z, float time, float e, int octaves, float[] out) {
		// Floored, not asserted: the epsilon is derived from a config-scaled constant and a caller
		// that passes 0 must produce still water, not NaN velocities that stick to a particle pool
		// forever. 1e-3 keeps the finite-difference estimate well above the float noise floor.
		float eps = Math.max(1.0E-3F, e);
		float n1 = fbm(x, y + eps, z + time, octaves);
		float n2 = fbm(x, y - eps, z + time, octaves);
		float n3 = fbm(x, y, z + time + eps, octaves);
		float n4 = fbm(x, y, z + time - eps, octaves);
		float n5 = fbm(x + eps, y, z + time, octaves);
		float n6 = fbm(x - eps, y, z + time, octaves);
		float inv = 1.0F / (2.0F * eps);
		out[0] = (n1 - n2) * inv - (n3 - n4) * inv;
		out[1] = (n3 - n4) * inv - (n5 - n6) * inv;
		out[2] = (n5 - n6) * inv - (n1 - n2) * inv;
	}

	/**
	 * Cheap 2D value noise for screen-space effects (heat haze, lens dirt motion).
	 * Tiling is not required here so the hash is direct.
	 */
	public static float value2(float x, float y, int seed) {
		float ix = (float) Math.floor(x);
		float iy = (float) Math.floor(y);
		float fx = x - ix;
		float fy = y - iy;
		int x0 = (int) ix;
		int y0 = (int) iy;
		float a = MathUtil.hash01(MathUtil.hash(x0, y0, seed));
		float b = MathUtil.hash01(MathUtil.hash(x0 + 1, y0, seed));
		float c = MathUtil.hash01(MathUtil.hash(x0, y0 + 1, seed));
		float d = MathUtil.hash01(MathUtil.hash(x0 + 1, y0 + 1, seed));
		float u = fade(fx);
		float v = fade(fy);
		return MathUtil.mix(MathUtil.mix(a, b, u), MathUtil.mix(c, d, u), v);
	}
}
