package com.doomsday.nukes.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The curve maths every visual envelope is built from. These assertions are chosen so each one is
 * <em>derivable from the implementation</em> rather than tuned to whatever it currently produces:
 * exact values at the boundary points, monotonicity, and closed-form identities. A test that only
 * pins today's numbers is a refactor blocker; a test that pins the intent is a regression net.
 *
 * <p>No Minecraft class is loaded here, which is the point of testing this layer: {@code build.gradle}
 * puts the remap jar on the test classpath, but nothing in this file needs it, so {@code ./gradlew
 * test} runs on a bare JDK.</p>
 */
final class MathUtilTest {
	private static final float EPS = 1.0E-4F;

	@Test
	void clampsSaturateAndOrderDegenerateBounds() {
		assertEquals(0.0F, MathUtil.clamp01(-3.0F));
		assertEquals(1.0F, MathUtil.clamp01(3.0F));
		assertEquals(0.5F, MathUtil.clamp01(0.5F));
		// A degenerate range (lo > hi, only reachable if a config sanitizer forgets to order its
		// bounds) must not throw or return v. The tie-break is "compare lo first, else hi", so the
		// answer is hi; pinned here because flipping the order would silently change every clamped
		// value in the config path rather than fail loudly.
		assertEquals(1.0F, MathUtil.clamp(5.0F, 2.0F, 1.0F));
		assertEquals(-1, MathUtil.clamp(-5, 0, 3));
	}

	@Test
	void mixAndLerpInterpolateLinearly() {
		assertEquals(0.0F, MathUtil.mix(0.0F, 10.0F, 0.0F), EPS);
		assertEquals(5.0F, MathUtil.mix(0.0F, 10.0F, 0.5F), EPS);
		assertEquals(10.0F, MathUtil.mix(0.0F, 10.0F, 1.0F), EPS);
		assertEquals(3.0D, MathUtil.lerp(1.0D, 5.0D, 0.5D), 1.0E-9);
		// t is not clamped by mix on purpose (callers pre-clamp); documenting it here so the next
		// reader does not "fix" it and silently change every envelope.
		assertEquals(-5.0F, MathUtil.mix(0.0F, 10.0F, -0.5F), EPS);
	}

	@Test
	void smoothstepIsFlatAtBothEnds() {
		assertEquals(0.0F, MathUtil.smoothstep(1.0F, 2.0F, 0.5F), EPS);
		assertEquals(1.0F, MathUtil.smoothstep(1.0F, 2.0F, 9.0F), EPS);
		assertEquals(0.0F, MathUtil.smoothstep(1.0F, 2.0F, 1.0F), EPS);
		assertEquals(1.0F, MathUtil.smoothstep(1.0F, 2.0F, 2.0F), EPS);
		assertEquals(0.5F, MathUtil.smoothstep(0.0F, 1.0F, 0.5F), EPS,
			"the midpoint of a Hermite smoothstep is exactly 0.5");
		// Degenerate span must not divide by zero: it becomes a hard step.
		assertEquals(0.0F, MathUtil.smoothstep(1.0F, 1.0F, 0.0F));
		assertEquals(1.0F, MathUtil.smoothstep(1.0F, 1.0F, 1.0F));
	}

	@Test
	void blastEnvelopeStartsAtZeroPeaksAtAttackAndFades() {
		float attack = 0.1F;
		float decay = 0.5F;
		assertEquals(0.0F, MathUtil.blastEnvelope(0.0F, attack, decay), EPS,
			"no frame of the effect may be visible at t=0 — a pop at stage start is the bug this "
				+ "envelope exists to prevent");
		assertEquals(1.0F, MathUtil.blastEnvelope(attack, attack, decay), EPS,
			"the attack ramp must reach exactly full strength");
		assertTrue(MathUtil.blastEnvelope(0.9F, attack, decay) < MathUtil.blastEnvelope(0.5F, attack, decay),
			"the tail must be monotonically quieter");
		assertTrue(MathUtil.blastEnvelope(0.05F, attack, decay) < 1.0F);
		// Out-of-range progress is clamped inside, so a late query (t > 1, which the client does hit
		// while a stage is being replaced) cannot produce a negative alpha.
		float tail = MathUtil.blastEnvelope(4.0F, attack, decay);
		assertTrue(tail >= 0.0F && tail <= 1.0F);
		// A zero attack would divide by zero; the implementation floors it.
		assertTrue(Float.isFinite(MathUtil.blastEnvelope(0.0F, 0.0F, 0.0F)));
	}

	@Test
	void decayIsBoundedAndNeverGrows() {
		assertEquals(1.0, MathUtil.decay(0.0D, 5.0D), 1.0E-9);
		assertEquals(1.0, MathUtil.decay(5.0D, 0.0D), 1.0E-9);
		assertEquals(Math.exp(-1.0D), MathUtil.decay(1.0D, 1.0D), 1.0E-9);
		// A negative time is a client that jumped backwards; the floor at 0 keeps the value at 1
		// instead of letting it explode.
		assertEquals(1.0, MathUtil.decay(-9.0D, 2.0D), 1.0E-9);
		assertTrue(MathUtil.decay(2.0D, 1.0D) < MathUtil.decay(1.0D, 1.0D));
	}

	@Test
	void attenuationSaturatesAtOriginAndVanishesBeyondMax() {
		assertEquals(1.0D, MathUtil.attenuation(0.0D, 8.0D, 64.0D, 2.0D), 1.0E-6,
			"f(0) must be exactly 1: every caller multiplies a full-strength effect by this");
		double a = MathUtil.attenuation(6.0D, 8.0D, 64.0D, 2.0D);
		double b = MathUtil.attenuation(24.0D, 8.0D, 64.0D, 2.0D);
		double c = MathUtil.attenuation(64.0D, 8.0D, 64.0D, 2.0D);
		assertTrue(a >= b && b >= c, "the curve must never get louder with distance");
		assertTrue(c < 0.02D, "at the configured maximum the effect is gone for practical purposes");
		// The reference distance is what removes the singularity: distances below it saturate.
		assertTrue(MathUtil.attenuation(1.0D, 8.0D, 64.0D, 2.0D) > 0.98D);
		// Zero/negative reference and max are config values a user can type; nothing may NaN.
		assertTrue(Double.isFinite(MathUtil.attenuation(0.0D, 0.0D, 0.0D, 0.0D)));
		assertTrue(Double.isFinite(MathUtil.attenuation(-4.0D, 8.0D, 2.0D, -3.0D)));
	}

	@Test
	void blackbodyMovesFromRedToBlueAndStaysInRange() {
		float[] ember = MathUtil.blackbody(1500.0F);
		assertEquals(3, ember.length);
		for (float v : ember) {
			assertTrue(v >= 0.0F && v <= 1.0F, "components are normalised to 0..1 for VertexConsumer");
		}
		// 1500 K is below the blue term's threshold, so blue is exactly zero and red dominates: this
		// is the fireball's cooling tail.
		assertEquals(0.0F, ember[2]);
		assertEquals(1.0F, ember[0], 1.0E-3F);
		assertTrue(ember[0] > ember[1]);
		float[] hot = MathUtil.blackbody(40000.0F);
		assertEquals(1.0F, hot[2], 1.0E-3F, "at extreme temperature blue saturates");
		assertTrue(hot[0] < 1.0F, "and red rolls off, which is what makes the flash white-blue");
		// Clamped input range: a config typo must not produce an out-of-gamut colour.
		float[] nonsense = MathUtil.blackbody(-100.0F);
		for (float v : nonsense) {
			assertTrue(v >= 0.0F && v <= 1.0F);
		}
	}

	@Test
	void hashingIsDeterministicAndInRange() {
		assertEquals(MathUtil.hash(3, -7, 128), MathUtil.hash(3, -7, 128));
		assertTrue(MathUtil.hash(3, -7, 128) != MathUtil.hash(3, -7, 129), "a neighbour cell must differ");
		for (int i = 0; i < 4096; i++) {
			float f = MathUtil.hash01(MathUtil.hash(i, i * 7, i * 13));
			assertTrue(f >= 0.0F && f < 1.0F, "hash01 is [0,1) — 1.0 would overshoot an alpha");
			float s = MathUtil.signed01(f > 0.5F ? 12345 : -67890);
			assertTrue(s >= -1.0F && s <= 1.0F);
		}
		assertEquals(MathUtil.hash(1L, 2), MathUtil.hash(1L, 2));
	}

	@Test
	void xorshiftNeverCollapsesToZero() {
		// The pooled particle spawner keeps its generator state in an int. If the iteration could
		// reach 0 the spawner would freeze (0 is a fixed point of every xorshift), so the zero seed is
		// remapped and 0 must never be produced from a live state.
		int state = MathUtil.nextInt(0);
		assertTrue(state != 0);
		for (int i = 0; i < 4096; i++) {
			state = MathUtil.nextInt(state);
			assertTrue(state != 0, "xorshift reached its fixed point at iteration " + i);
		}
	}

	@Test
	void constantsAreWhatTheFormulasAssume() {
		assertEquals(0.000001D, MathUtil.EPS, 0.0D);
		assertEquals((float) Math.PI, MathUtil.PI_F, 1.0E-7F);
		assertEquals(2.0F * (float) Math.PI, MathUtil.TWO_PI_F, 1.0E-6F);
	}
}
