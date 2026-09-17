package com.doomsday.nukes.entity;

import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.config.DoomsdayConfig;
import com.doomsday.nukes.util.MathUtil;
import net.minecraft.entity.EntityType;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Stage 5 — the fallout field: the reason a detonation is still interesting 180 seconds later.
 *
 * <h2>One entity, one field, no per-flake objects</h2>
 * This entity does <b>not</b> represent a particle. It represents the region: it carries the centre,
 * the spread radius, the wind vector and the remaining density, and the client particle pool
 * ({@code client/particle/AshPool}) samples it to decide where to put ash this frame. A Tsar Bomba
 * footprint is therefore one entity plus at most {@code maxAshParticles} pooled quads, not a
 * hundred thousand entities — which is the difference between "cinematic" and "unplayable".
 *
 * <h2>Density is a decay, not a countdown</h2>
 * {@link #density()} falls off exponentially over {@code ashFallSeconds}, so the tail is long and
 * gentle: the rain thins rather than stopping on a tick boundary. The matching contamination field
 * in {@code DoomsdayWorldData} is what actually hurts you; this entity is only its visible part,
 * which is why removing it (config {@code falloutEnabled = false}) changes pixels and nothing else.
 */
public class FalloutEntity extends VisualEffectEntity {
	private double spreadRadius = 240.0D;
	private double fallSeconds = 180.0D;
	private int ashBudget = 2600;
	private Vec3d wind = new Vec3d(1.0D, 0.0D, 0.0D);
	private double windStrength = 0.35D;
	private int seed;

	public FalloutEntity(EntityType<? extends FalloutEntity> type, World world) {
		super(type, world);
	}

	public void applyStageConfig(double geometryScaleForYield) {
		DoomsdayConfig c = ConfigManager.get();
		this.geometryScale = Math.max(0.25D, geometryScaleForYield);
		this.fallSeconds = Math.max(5.0D, c.ashFallSeconds);
		this.lifetime = Math.max(100, DoomsdayConfig.ticks((float) this.fallSeconds));
		this.spreadRadius = Math.max(8.0D, c.falloutSpreadBlocks * this.geometryScale);
		this.ashBudget = MathUtil.clamp(c.maxAshParticles, 100, 20000);
		// Wind is derived from the epicentre hash, not from a random: the same crater must rain in
		// the same direction for every observer, or two players see two different plumes.
		this.seed = MathUtil.hash((int) Math.floor(this.origin.x), (int) Math.floor(this.origin.z),
			4117);
		double angle = MathUtil.hash01(this.seed) * MathUtil.TWO_PI_F;
		this.wind = new Vec3d(Math.cos(angle), 0.0D, Math.sin(angle)).normalize();
		this.windStrength = 0.15D + 0.5D * MathUtil.hash01(this.seed ^ 0x5f3a);
	}

	/** 0..1 how heavy the rain is right now. */
	public double density() {
		float p = progress();
		// exp(-3p) with a floor: at the end of the window there is still a trace, which reads as
		// "it is still falling somewhere", and it never snaps to zero.
		return Math.max(0.0D, MathUtil.decay(p, 3.0D));
	}

	/** Current ash radius: the plume drifts downwind and widens as it goes. */
	public double currentRadius() {
		float p = progress();
		return spreadRadius * (0.25D + 0.75D * MathUtil.clamp01(p * 1.6D));
	}

	/** Downwind offset of the field's centre at the current progress. */
	public Vec3d centre() {
		double drift = this.windStrength * currentRadius() * progress() * 0.6D;
		return new Vec3d(this.origin.x + this.wind.x * drift,
			this.origin.y + this.riseAltitude(),
			this.origin.z + this.wind.z * drift);
	}

	/** Ash starts high (still in the cloud) and the column descends as the field ages. */
	public double riseAltitude() {
		float p = progress();
		return Math.max(0.0D, (1.0D - p) * 90.0D * MathUtil.clamp01(this.radius * 0.02D + 0.4D));
	}

	public Vec3d wind() {
		return wind;
	}

	public double windStrength() {
		return windStrength;
	}

	public int ashBudget() {
		return ashBudget;
	}

	public double spreadRadius() {
		return spreadRadius;
	}

	/**
	 * Deterministic sample position for ash particle {@code index} this tick: a point inside the
	 * current radius, above the ground, drifting downwind. Exposed so the pool can be driven
	 * entirely from numbers the client already has — no per-particle state on the entity.
	 */
	public Vec3d samplePosition(int index, long worldTime) {
		int h = MathUtil.hash(seed, index * 7 + (int) (worldTime & 0xFFFF));
		double r = Math.sqrt(MathUtil.hash01(h)) * currentRadius();
		double a = MathUtil.hash01(h ^ 0x9e37) * MathUtil.TWO_PI_F;
		double x = this.origin.x + Math.cos(a) * r + this.wind.x * this.windStrength * r * 0.25D;
		double z = this.origin.z + Math.sin(a) * r + this.wind.z * this.windStrength * r * 0.25D;
		double y = this.origin.y + 40.0D + MathUtil.hash01(h ^ 0x1234) * 140.0D;
		return new Vec3d(x, y, z);
	}

	@Override
	protected void onExpired() {
	}
}
