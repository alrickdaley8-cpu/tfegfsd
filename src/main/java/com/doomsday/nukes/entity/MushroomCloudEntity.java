package com.doomsday.nukes.entity;

import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.config.DoomsdayConfig;
import com.doomsday.nukes.util.MathUtil;
import com.doomsday.nukes.util.NoiseField;
import net.minecraft.entity.EntityType;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Stage 4 — the mushroom cloud. The single most visible thing in this mod, and the one people
 * will judge it by at 3 km.
 *
 * <h2>Geometry model</h2>
 * The cloud is not a mesh: it is three parametric families the renderer samples —
 * <b>stem</b> ({@code cloudStemSegments} stacked rings from the crater to the cap),
 * <b>cap</b> ({@code cloudCapSamples} billboard puffs on a torus-ish dome that keeps widening),
 * and <b>skirt</b> ({@code cloudSkirtSamples} puffs curling back down at the cap's edge). The
 * sample counts are config values because they are the quality knob, not an implementation detail:
 * LOW drops them, ULTRA raises them, and this class clamps to sane ranges so a hand-edited 0 can
 * never divide by zero.
 *
 * <h2>Rise</h2>
 * {@link #verticalDrift()} lifts the whole entity by {@code cloudRiseBlocks} across its lifetime
 * with a saturating curve — real clouds decelerate as they reach their equilibrium level, and a
 * linear rise is the single most obvious tell of a cheap effect. Because the drift is part of the
 * entity position, the renderer's frustum test, the LOD distance and the ground shadow all move
 * with it for free.
 *
 * <h2>Visibility</h2>
 * {@link #shouldRender(double, double, double, float)} replaces vanilla's "is the 1-block bounding
 * box in view" test with a distance test against {@code cloudVisibilityDistance} (spec: 3000+
 * blocks), so the cloud stays on screen past the render distance rather than popping out of
 * existence at chunk borders. It is the reason {@code ignoreCameraFrustum} is set in the base
 * class. This is a *drawing* decision only: the cloud has always been invisible to lighting and
 * collision, so nothing about the simulation changes with distance.
 */
public class MushroomCloudEntity extends VisualEffectEntity {
	private double riseBlocks = 150.0D;
	private int stemSegments = 20;
	private int capSamples = 100;
	private int skirtSamples = 28;
	private double capRadiusBlocks = 74.0D;
	private double lodNear = 220.0D;
	private double lodFar = 900.0D;
	private double turbulence = 1.0D;
	private double viewDistance = 3400.0D;
	private boolean emissiveAtNight = true;
	private int seed;

	public MushroomCloudEntity(EntityType<? extends MushroomCloudEntity> type, World world) {
		super(type, world);
	}

	/** Pull the per-stage config in, clamped. Called by {@code DoomsdayVisuals} after configure. */
	public void applyStageConfig(double geometryScaleForYield) {
		DoomsdayConfig c = ConfigManager.get();
		DoomsdayConfig.Quality q = c.quality == null ? DoomsdayConfig.Quality.MEDIUM : c.quality;
		// The quality tier scales sample counts, never the physical size: a small cloud drawn with
		// fewer puffs still occupies the same world volume, which is the only part a player can
		// cross-check against the crater.
		double detail = MathUtil.clamp(q.sampleScale, 0.25D, 1.0D);
		this.geometryScale = Math.max(0.25D, geometryScaleForYield);
		this.lifetime = Math.max(60, ConfigManager.ticks(c.cloudLifetimeSeconds));
		this.riseBlocks = Math.max(8.0D, c.cloudRiseBlocks * this.geometryScale);
		this.stemSegments = MathUtil.clamp((int) Math.round(c.cloudStemSegments * detail), 4, 64);
		// c.cloudCapSamples is the *logical* sample count from the spec (100); q.cloudCapSamples is
		// the per-layer budget the tier allows. Taking the min keeps a ULTRA config from being read
		// as "more geometry than the tier was chosen to draw".
		this.capSamples = MathUtil.clamp(
			(int) Math.round(Math.min(c.cloudCapSamples, q.cloudCapSamples * 3) * detail), 12, 320);
		this.skirtSamples = MathUtil.clamp((int) Math.round(c.cloudSkirtSamples * detail), 6, 96);
		this.capRadiusBlocks = Math.max(4.0D,
			(c.cloudCapRadius * this.geometryScale) * Math.max(0.6D, Math.cbrt(this.yieldKt) * 0.5D
				+ 0.5D));
		this.lodNear = Math.max(40.0D, c.cloudLodNearDistance);
		this.lodFar = Math.max(this.lodNear + 40.0D, c.cloudLodFarDistance);
		this.turbulence = Math.max(0.0D, c.cloudTurbulence);
		this.viewDistance = Math.max(256.0D, c.cloudVisibilityDistance);
		this.emissiveAtNight = c.cloudEmissiveAtNight;
		this.seed = MathUtil.hash((int) Math.floor(this.origin.x), (int) Math.floor(this.origin.y),
			(int) Math.floor(this.origin.z));
	}

	/** Cap centre height above the epicentre, saturating toward {@link #riseBlocks}. */
	public double riseHeight() {
		float p = progress();
		// 1 - (1-p)^2 : fast early, easing off — the buoyancy curve of a real fireball.
		double eased = 1.0D - (1.0D - p) * (1.0D - p);
		return riseBlocks * eased;
	}

	@Override
	protected double verticalDrift() {
		return riseHeight();
	}

	/** Current cap radius in blocks: widens as the cloud spreads laterally at altitude. */
	public double capRadius() {
		float p = progress();
		double spread = 1.0D + 0.85D * MathUtil.smoothstep(0.15F, 1.0F, p);
		return capRadiusBlocks * spread;
	}

	/** Skirt radius, slightly larger than the cap and only opening after the stem reaches it. */
	public double skirtRadius() {
		float p = progress();
		return capRadius() * (1.0D + 0.35D * MathUtil.smoothstep(0.25F, 0.9F, p));
	}

	/** Stem radius: fat at the bottom (dust), narrow at the top (condensation). */
	public double stemRadius(double t) {
		double k = MathUtil.clamp01(t);
		return capRadius() * (0.42D - 0.26D * k) * (1.0D + 0.1D * Math.sin(k * 6.2831853D));
	}

	/** Global opacity: quick fade-in, long hold, fade-out over the last 30 % of the lifetime. */
	public double opacity() {
		float p = progress();
		double in = MathUtil.smoothstep(0.0F, 0.06F, p);
		double out = 1.0D - MathUtil.smoothstep(0.7F, 1.0F, p);
		return Math.min(in, out);
	}

	/**
	 * Boiling displacement for a puff at (index, phase), in blocks. The noise field is the same
	 * {@link MathUtil#hash(int, int, int)} lattice the terrain generator uses, so the cloud's
	 * turbulence is deterministic — two clients rendering the same event see the same puffs, which
	 * is what makes screenshots and replays agree.
	 */
	public double boil(int index, double phase) {
		// Coherent fbm, not a per-sample hash: with a hash, neighbouring puffs on the cap move
		// independently, so the silhouette fizzes instead of rolling. Sampling the field along a
		// smooth 1-D walk over the puff index (offset by the cloud seed so two clouds of the same
		// yield do not boil in lock-step) gives neighbouring puffs correlated motion, which is what
		// reads as convection. Time enters through the third axis, at a rate that keeps the field's
		// 256-unit period from being walked off by a 90-second cloud.
		float nx = (seed % 512) * 0.31F + index * 0.17F;
		float ny = index * 0.07F;
		float nz = (float) (phase * 0.6D);
		float n = NoiseField.fbm(nx, ny, nz, 3);
		return (n * 0.5D + 0.5D) * turbulence * capRadius() * 0.06D;
	}

	/** Where the cap sits in world space, for the shadow anchor and the sky-darkening centre. */
	public Vec3d capCenter() {
		return new Vec3d(this.origin.x, this.origin.y + riseHeight(), this.origin.z);
	}

	public int stemSegments() {
		return stemSegments;
	}

	public int capSamples() {
		return capSamples;
	}

	public int skirtSamples() {
		return skirtSamples;
	}

	public double lodNear() {
		return lodNear;
	}

	public double lodFar() {
		return lodFar;
	}

	public boolean emissiveAtNight() {
		return emissiveAtNight;
	}

	public double viewDistance() {
		return viewDistance;
	}

	@Override
	protected void onExpired() {
		// The cloud never leaves debris behind: the fallout field owns the particles, and ash keeps
		// falling after the cloud is gone. Letting this entity simply expire is what keeps the
		// visual entity count at maxVisualEntities rather than "one per crater".
	}
}
