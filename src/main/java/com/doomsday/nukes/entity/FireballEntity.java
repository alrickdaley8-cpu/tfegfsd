package com.doomsday.nukes.entity;

import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.config.DoomsdayConfig;
import com.doomsday.nukes.util.MathUtil;
import net.minecraft.entity.EntityType;
import net.minecraft.world.World;

/**
 * Stage 2 — the fireball.
 *
 * <p>One entity, drawn as an expanding emissive shell. The geometry (a subdivided icosphere in the
 * renderer, displaced by {@code NoiseField}) is *derived* from {@link #radiusAt(float)} so there is
 * exactly one curve to reason about, and the same curve is what the server used to decide how far
 * the fireball terrifies terrain. Visual and mechanical radius are the same number by construction —
 * that is the property that makes a nuke mod feel honest rather than decorative.</p>
 *
 * <p>Lifetime is {@code fireballGrowSeconds + fireballHoldSeconds + fireballFadeSeconds}; the
 * three-phase shape is passed to {@link VisualEffectEntity#envelope} as fractions, so changing any
 * one of them in the config moves the animation instead of desyncing it from the entity's death.</p>
 */
public class FireballEntity extends VisualEffectEntity {
	private double growFraction = 0.26D;
	private double holdFraction = 0.18D;
	private double fadeFraction = 0.56D;
	private double startRadius = 5.0D;
	private int colorSeed;

	public FireballEntity(EntityType<? extends FireballEntity> type, World world) {
		super(type, world);
	}

	/** Read the stage timing from config and split the lifetime accordingly. */
	public void applyStageConfig() {
		DoomsdayConfig c = ConfigManager.get();
		double grow = Math.max(0.05D, c.fireballGrowSeconds);
		double hold = Math.max(0.0D, c.fireballHoldSeconds);
		double fade = Math.max(0.1D, c.fireballFadeSeconds);
		double total = grow + hold + fade;
		this.growFraction = grow / total;
		this.holdFraction = hold / total;
		this.fadeFraction = fade / total;
		this.lifetime = Math.max(20, ConfigManager.ticks((float) total));
		this.startRadius = Math.max(0.5D, c.fireballStartRadius);
		this.colorSeed = MathUtil.hash((int) Math.floor(this.origin.x),
			(int) Math.floor(this.origin.y), (int) Math.floor(this.origin.z));
	}

	/** Current outer radius in blocks. */
	public double radiusAt(float progress) {
		double e = envelope(progress, growFraction, holdFraction, fadeFraction);
		return MathUtil.lerp(startRadius, radius, e);
	}

	/** Radius for this entity's current age — the renderer's only geometry input. */
	public double currentRadius() {
		return radiusAt(progress());
	}

	/**
	 * Surface temperature of the fireball in Kelvin: ~8200 K at the apex (roughly a B star, and
	 * about what an airburst fireball reads) falling to ~1600 K of incandescent cooling as the
	 * shell expands. The renderer turns this into RGB with
	 * {@link MathUtil#blackbody(float)} — the same curve the flash uses, so fireball and flash
	 * cannot drift apart in colour.
	 */
	public float temperature() {
		float p = progress();
		return (float) MathUtil.lerp(8200.0D, 1600.0D, (double) p * (double) p);
	}

	/** Packaged colour for this tick: r,g,b in 0..1 plus the intensity alpha. */
	public float[] surfaceColor() {
		float[] rgb = MathUtil.blackbody(temperature());
		return new float[]{rgb[0], rgb[1], rgb[2], (float) intensity()};
	}

	/** 0..1 screen intensity: full white while growing, then decaying with the fade phase. */
	public double intensity() {
		float p = progress();
		if (p < growFraction) {
			return 1.0D;
		}
		double t = (p - growFraction) / Math.max(0.001D, 1.0D - growFraction);
		return Math.max(0.0D, 1.0D - t * t);
	}

	/** Jitter seed so two fireballs in the same world do not boil in lockstep. */
	public int colorSeed() {
		return colorSeed;
	}

	public double startRadius() {
		return startRadius;
	}

	@Override
	protected void onExpired() {
		// Nothing to release: the renderer owns no buffers and the particle budget is spent by
		// DoomsdayVisuals, which watches remainingTicks() to stop spawning embers.
	}
}
