package com.doomsday.nukes.client.particle;

import com.doomsday.nukes.client.ClientTicks;
import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.config.DoomsdayConfig;
import com.doomsday.nukes.entity.FalloutEntity;
import com.doomsday.nukes.util.MathUtil;
import com.doomsday.nukes.util.NoiseField;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.Vec3d;

/**
 * Budgeted ash emission for the fallout field.
 *
 * <h2>Why a pool instead of {@code emitParticles} in the entity's tick</h2>
 * A fallout field is the one effect whose naive cost is proportional to the area of the crater
 * (a 100 kt footprint is ~4000 chunks-worth of surface if you emit per column). This class inverts
 * it: a fixed per-tick emission budget, spent on randomly sampled columns from the
 * {@link FalloutEntity} that owns the field. Cost is therefore O(budget), never O(crater), and the
 * look converges to "it is raining ash everywhere" because the sample positions change every tick.
 *
 * <h2>Two caps, not one</h2>
 * {@code maxAshParticles} bounds this effect; {@code maxParticles} bounds every mod particle at
 * once (fireballs, embers, ash, steam). Both are enforced here — the local one so ash cannot starve
 * the fireball, the global one so a cascade of six Tsar Bombas cannot starve the frame.
 *
 * <h2>Adaptive mode</h2>
 * When {@code adaptiveQuality} is on and the client's own {@code avgChunkUpdates}-adjacent frame
 * budget is missed (measured here as the interval between {@link #tick} calls exceeding
 * {@code adaptiveBudgetMs}), the emission rate is halved until two consecutive frames come back
 * under budget. That is a deliberate clamp rather than a curve: an effect that fades in and out on
 * a moving average visibly pulses, and a halving that stays halved does not.
 */
public final class AshPool {
	private static int live;
	private static int emitted;
	private static long lastTickNanos;
	private static double degradedUntil;
	private static boolean degraded;

	private AshPool() {
	}

/** Called once per client tick, after {@code DoomsdayVisuals.tick()}. */
	public static void tick(java.util.List<FalloutEntity> fields) {
		DoomsdayConfig c = ConfigManager.get();
		if (!c.falloutEnabled || !c.atmosphericAftermath) {
			live = 0;
			return;
		}
		measure(c);
		// Particles age on the client's own clock, so the only bookkeeping needed here is a
		// decrement: an ash particle lives ~4 s, so removing one per emission keeps the count
		// honest without the pool having to hold references to a thousand Particle objects.
		live = Math.max(0, live - 1);
		int budget = ashBudget(c);
		if (budget <= 0) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (!(client.world instanceof ClientWorld world) || client.player == null) {
			return;
		}
		Vec3d eye = client.player.getEyePos();
		int spent = 0;
		int perField = Math.max(1, budget / Math.max(1, fields.size()));
		double strongest = 0.0D;
		for (FalloutEntity field : fields) {
			if (field.isExpired() || field.density() <= 0.01D || spent >= budget) {
				continue;
			}
			strongest = Math.max(strongest, field.density());
			for (int i = 0; i < perField && spent < budget; i++) {
				if (emit(world, field, eye, c)) {
					spent++;
					live++;
					emitted++;
				}
			}
		}
		// The budget tracks the fade curve through this one value, so the rain thins with the
		// field instead of stopping on the tick the entity expires.
		noteDensity(strongest);
	}

	private static int ashBudget(DoomsdayConfig c) {
		int perAsh = MathUtil.clamp(c.maxAshParticles, 0, 20000);
		int perAll = MathUtil.clamp(c.maxParticles, 0, 40000);
		int room = Math.min(perAsh, perAll) - live;
		if (room <= 0) {
			return 0;
		}
		// Emission rate = budget / mean lifetime, so `live` never exceeds the cap by more than the
		// rounding of one tick. Divide by 80 ticks (4 s of particle life) and scale by density.
		double density = currentDensity();
		int rate = (int) Math.ceil(room / 80.0D * (0.35D + 0.65D * density)
			* MathUtil.clamp(c.particleDensity, 0.25D, 1.0D));
		if (isDegraded()) {
			rate = rate / 2;
		}
		return Math.max(0, Math.min(rate, room));
	}

	private static final float[] CURL = new float[3];

	/**
	 * Local xorshift state rather than {@link Math#random()}: the shared Random behind Math.random is
	 * synchronised, and this loop can run {@code particleBatchSize} times per tick next to vanilla's
	 * own particle emitters, all on the same thread.
	 */
	private static int randState = 0x9E3779B9;

	private static double nextRandom01() {
		randState = MathUtil.nextInt(randState);
		return MathUtil.hash01(randState);
	}

	private static double density;

	private static void setDensity(double d) {
		density = MathUtil.clamp(d, 0.0D, 1.0D);
	}

	private static double currentDensity() {
		return density;
	}

	/** The fallout field updates this once per tick so the budget tracks the fade curve. */
	public static void noteDensity(double maxDensity) {
		setDensity(maxDensity);
	}

	private static boolean emit(ClientWorld world, FalloutEntity field, Vec3d eye,
								DoomsdayConfig c) {
		int index = (int) (ClientTicks.get() * 31L + nextRandom01() * 4096.0D) & 0x3FFF;
		Vec3d pos = field.samplePosition(index, world.getTime());
		double cull = Math.max(32.0D, c.particleCullDistance);
		if (pos.squaredDistanceTo(eye) > cull * cull) {
			return false;
		}
		Vec3d wind = field.wind();
		double strength = field.windStrength();
		ParticleEffect effect = nextRandom01() < 0.35D ? ParticleTypes.ASH
			: ParticleTypes.CAMPFIRE_COSY_SMOKE;
		// Divergence-free curl noise sampled at the particle's own position: it shears the ash sideways
		// the way a real plume does, and because the field is incompressible it never collects the
		// particles into clumps or empties them out — which per-axis noise does, visibly, after ~20 s.
		// CURL is shared scratch: this runs once per emitted particle on the render thread, and an
		// allocation per particle is exactly how a particle system turns into a GC hitch.
		NoiseField.curl((float) (pos.x * 0.03D), (float) (pos.y * 0.03D), (float) (pos.z * 0.03D),
			ClientTicks.get() * 0.004F, 0.25F, 2, CURL);
		double swirl = 0.045D * (1.0D + strength);
		// addAlwaysVisibleParticle(effect, alwaysShow, x, y, z, vx, vy, vz): the "always visible"
		// variant is the only one that does not disappear when the block behind it is culled,
		// which matters because ash falls through geometry that the terrain pass just deleted.
		world.addAlwaysVisibleParticle(effect, true,
			pos.x, pos.y, pos.z,
			wind.x * strength * 0.12D + CURL[0] * swirl,
			-0.055D - nextRandom01() * 0.02D + CURL[1] * swirl * 0.4D,
			wind.z * strength * 0.12D + CURL[2] * swirl);
		return true;
	}

	private static void measure(DoomsdayConfig c) {
		long now = System.nanoTime();
		long previous = lastTickNanos;
		lastTickNanos = now;
		if (previous == 0L) {
			return;
		}
		double frameMs = (now - previous) / 1.0E6D;
		double budget = Math.max(4.0D, c.adaptiveBudgetMs);
		if (!c.adaptiveQuality) {
			degraded = false;
			degradedUntil = 0.0D;
			return;
		}
		long tick = ClientTicks.get();
		if (frameMs > budget * 1.6D) {
			degraded = true;
			degradedUntil = tick + 40L;
		} else if (tick > degradedUntil) {
			degraded = false;
		}
	}

	private static boolean isDegraded() {
		return degraded;
	}

	public static int liveCount() {
		return live;
	}

	public static String describe() {
		return "ash pool: " + live + " live, " + emitted + " emitted, "
			+ (degraded ? "degraded" : "full rate");
	}

	public static void clear() {
		live = 0;
		emitted = 0;
		degraded = false;
		degradedUntil = 0.0D;
		density = 0.0D;
	}
}
