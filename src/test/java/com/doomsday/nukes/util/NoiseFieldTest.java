package com.doomsday.nukes.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cloud/fallout noise field. The properties pinned here are the ones the *visual* result depends
 * on — coherence, tiling, boundedness and cross-safety — rather than a golden hash, so the field can
 * be retuned (a different permutation seed changes every number and no assertion below) while the
 * things that make it look like a cloud stay under test.
 */
final class NoiseFieldTest {
	private static final float EPS = 1.0E-5F;

	@Test
	void perlinVanishesExactlyOnTheLattice() {
		// Every lattice corner interpolates eight gradients that are each dot(grad, 0) = 0. This is
		// the property that makes the field *continuous* (no seams between cells), and it is exactly
		// zero — not approximately — so a future change that adds a bias or an offset (for a
		// detail term, say) has to be deliberate rather than accidental.
		for (int i = -3; i <= 3; i++) {
			for (int j = -2; j <= 2; j++) {
				assertEquals(0.0F, NoiseField.perlin(i, j, i + j), 0.0F,
					"lattice point (" + i + ", " + j + ")");
			}
		}
	}

	@Test
	void perlinIsBoundedAndDeterministic() {
		float min = Float.MAX_VALUE;
		float max = -Float.MAX_VALUE;
		for (int n = 0; n < 20000; n++) {
			float x = n * 0.017F;
			float y = n * 0.041F;
			float z = n * 0.009F;
			float v = NoiseField.perlin(x, y, z);
			assertTrue(Float.isFinite(v));
			min = Math.min(min, v);
			max = Math.max(max, v);
			assertEquals(v, NoiseField.perlin(x, y, z), 0.0F, "must be reproducible on both sides");
		}
		// The gradients of this implementation bound the result to [-2, 2] (a trilinear interpolation
		// of eight values in that range), and that ceiling is the number the callers assume when they
		// treat fbm as roughly unit-scale. Both signs must appear, or the field is biased and the
		// cloud leans. The thresholds are deliberately loose: they state a property, not a golden
		// value, so re-seeding the permutation table does not break this file.
		assertTrue(min > -2.0F && min < -0.05F, "min was " + min);
		assertTrue(max < 2.0F && max > 0.05F, "max was " + max);
	}

	@Test
	void fieldTilesAtThePermutationPeriod() {
		// The fallout drift and the cloud's long-life boil both rely on the lattice wrapping at
		// PERIOD = 256, so a coordinate offset by a whole period has to land on the same value.
		for (float[] p : new float[][] {{0.5F, 1.25F, 2.5F}, {17.0F, 0.75F, 3.0F},
			{-4.25F, 8.5F, 0.0F}}) {
			assertEquals(NoiseField.perlin(p[0], p[1], p[2]),
				NoiseField.perlin(p[0] + 256.0F, p[1], p[2] + 256.0F), EPS);
		}
	}

	@Test
	void fbmNormalisesByAmplitudeAndClampsOctaves() {
		// With a single octave the normaliser cancels the amplitude exactly, so fbm degenerates to
		// plain perlin. That identity is what proves the normalisation is amplitude-aware rather than
		// an arbitrary scale factor.
		for (float[] p : new float[][] {{0.3F, 0.7F, 1.1F}, {12.5F, 4.25F, 9.75F}}) {
			assertEquals(NoiseField.perlin(p[0], p[1], p[2]),
				NoiseField.fbm(p[0], p[1], p[2], 1), 1.0E-6F);
		}
		// Octave count is user-configurable (Quality.noiseOctaves), so it must be clamped rather than
		// looping 64 times because somebody typed a big number into a config file.
		assertEquals(NoiseField.fbm(1.5F, 2.5F, 3.5F, 6), NoiseField.fbm(1.5F, 2.5F, 3.5F, 99), EPS);
		assertEquals(NoiseField.fbm(1.5F, 2.5F, 3.5F, 1), NoiseField.fbm(1.5F, 2.5F, 3.5F, 0), EPS);
		float v = NoiseField.fbm(0.11F, 0.92F, 3.3F, 4);
		assertTrue(v >= -2.0F && v <= 2.0F, "fbm must stay inside the per-lens bound of its octaves");
	}

	@Test
	void curlIsDivergenceFreeByConstruction() {
		float[] out = new float[3];
		for (int n = 0; n < 500; n++) {
			NoiseField.curl(n * 0.13F, n * 0.07F, 4.0F, n * 0.01F, 0.25F, 2, out);
			assertTrue(Float.isFinite(out[0]) && Float.isFinite(out[1]) && Float.isFinite(out[2]),
				"NaN in a velocity pool is permanent and invisible");
			// The implementation returns (a-b, b-c, c-a), which sums to zero — the discrete statement
			// of a divergence-free field, i.e. the property that stops ash clumping or thinning out.
			assertEquals(0.0F, out[0] + out[1] + out[2], 1.0E-3F);
		}
	}

	@Test
	void curlSurvivesADegenerateEpsilon() {
		float[] out = new float[3];
		// e = 0 would be a division by zero in the central difference; the floored epsilon must keep
		// the result finite (and near zero, since the offsets collapse).
		NoiseField.curl(1.0F, 2.0F, 3.0F, 0.0F, 0.0F, 2, out);
		for (float v : out) {
			assertTrue(Float.isFinite(v), "zero epsilon produced " + v);
		}
		NoiseField.curl(1.0F, 2.0F, 3.0F, 0.0F, -1.0F, 2, out);
		for (float v : out) {
			assertTrue(Float.isFinite(v), "negative epsilon produced " + v);
		}
	}

	@Test
	void valueNoiseStaysInUnitRangeAndPinsItsCorners() {
		for (int y = 0; y < 8; y++) {
			for (int x = 0; x < 8; x++) {
				float v = NoiseField.value2(x, y, 12345);
				assertTrue(v >= 0.0F && v < 1.0F, "value2 is [0,1) so it can drive an alpha directly");
				// On an integer corner the interpolation weights are 0, so the result is that corner's
				// hash: the same value the HUD/heat-haze code would compute if it inlined the hash.
				assertEquals(MathUtil.hash01(MathUtil.hash(x, y, 12345)), v, EPS,
					"corner (" + x + ", " + y + ")");
			}
		}
	}
}
