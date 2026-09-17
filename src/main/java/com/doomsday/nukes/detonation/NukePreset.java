package com.doomsday.nukes.detonation;

import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.config.DoomsdayConfig;

/**
 * The four devices, their design values, and the yield→radius model.
 *
 * <h2>Scaling law</h2>
 * Every geometric quantity follows the cube-root of yield, which is the correct
 * physical behaviour for a blast in a fixed-density atmosphere
 * ({@code R ∝ E^(1/3)}), so a 8× yield increase doubles the radii rather than
 * multiplying them by eight. Time-like quantities (cloud lifetime, fallout) scale as
 * {@code E^(1/6)} — slower than linear, which is what keeps a Tsar Bomba readable
 * instead of a same-length event drawn bigger. The cap {@code maxEffectiveYieldKt}
 * is applied before scaling so a mis-edited config cannot produce an unbounded world
 * mutation.
 *
 * <p>All values are overridable per-device through {@code config/doomsday.json}
 * ({@link DoomsdayConfig.DeviceTuning}); a non-null override replaces the derived
 * value outright rather than multiplying it, which is what admins expect.</p>
 */
public enum NukePreset {
	/** Workhorse device. Compact, fast cloud, moderate contamination. */
	STANDARD_NUKE(
		"standard",
		20.0D,
		46.0D, 26.0D, 130.0D, 17.0D, 1.00D, 140.0D,
		8200.0F, 0xFFF3DCB4, 0.0F, 1.00F, 1.00F
	),
	/** Thin "gun-type" device: sharp fast flash, wide wave, quick decay. */
	LITTLE_BOY(
		"little_boy",
		15.0D,
		40.0D, 22.0D, 112.0D, 14.0D, 0.86D, 95.0D,
		9400.0F, 0xFFE4EEFF, 0.0F, 1.14F, 0.86F
	),
	/** "Implosion" device: slower, fatter fireball, heavy stem, long fallout. */
	FAT_MAN(
		"fat_man",
		21.0D,
		48.0D, 28.0D, 136.0D, 18.0D, 1.10D, 175.0D,
		7400.0F, 0xFFFFD08A, 0.0F, 0.88F, 1.22F
	),
	/** 100 kt class. Everything is bounded by the LOD system, so it stays playable. */
	TSAR_BOMBA(
		"tsar_bomba",
		100.0D,
		92.0D, 60.0D, 300.0D, 40.0D, 3.20D, 420.0D,
		6600.0F, 0xFFFFC27A, 0.0F, 0.66F, 1.85F
	);

	/** Key used in the {@code devices} map of the config file. */
	public final String configKey;
	public final double baseYieldKt;
	public final double baseBlastRadius;
	public final double baseFireballRadius;
	public final double baseShockwaveRadius;
	public final double baseCraterRadius;
	public final double baseCloudScale;
	public final double baseRadiationSeconds;

	// ——— visual identity (never read by the server, so it cannot desync anything) ———
	/** Blackbody colour temperature at the fireball core, in kelvin. */
	public final float fireballTemperatureK;
	/** Packed RGB tint of the cloud, per device. */
	public final int cloudTintArgb;
	/** Lean of the stem in degrees — gives each device a recognisable silhouette. */
	public final float stemLeanDegrees;
	/** Vertical stretch of the cap relative to the stem height. */
	public final float capAspect;
	/** Pitch of this device's sounds. */
	public final float soundPitch;

	NukePreset(String configKey, double baseYieldKt,
			   double baseBlastRadius, double baseFireballRadius, double baseShockwaveRadius,
			   double baseCraterRadius, double baseCloudScale, double baseRadiationSeconds,
			   float fireballTemperatureK, int cloudTintArgb, float stemLeanDegrees,
			   float capAspect, float soundPitch) {
		this.configKey = configKey;
		this.baseYieldKt = baseYieldKt;
		this.baseBlastRadius = baseBlastRadius;
		this.baseFireballRadius = baseFireballRadius;
		this.baseShockwaveRadius = baseShockwaveRadius;
		this.baseCraterRadius = baseCraterRadius;
		this.baseCloudScale = baseCloudScale;
		this.baseRadiationSeconds = baseRadiationSeconds;
		this.fireballTemperatureK = fireballTemperatureK;
		this.cloudTintArgb = cloudTintArgb;
		this.stemLeanDegrees = stemLeanDegrees;
		this.capAspect = capAspect;
		this.soundPitch = soundPitch;
	}

	/** Registry path for the block/item of this device. */
	public String blockId() {
		return "nuke_" + configKey;
	}

	/**
	 * @param multiplier yield multiplier after config clamping
	 * @return {@code E^(1/3)} — the geometric scale factor
	 */
	public double geometryScale(double multiplier) {
		return Math.cbrt(Math.max(0.001D, multiplier));
	}

	/** @return {@code E^(1/6)} — the temporal scale factor. */
	public double timeScale(double multiplier) {
		return Math.sqrt(Math.cbrt(Math.max(0.001D, multiplier)));
	}

	/** Absolute-yield geometric scale, anchored so 20 kt (a Standard Nuke) == 1.0. */
	public static double geometryScaleFor(double yieldKt) {
		return Math.cbrt(Math.max(0.05D, yieldKt) / 20.0D);
	}

	/** Absolute-yield temporal scale, anchored so 20 kt == 1.0. */
	public static double timeScaleFor(double yieldKt) {
		return Math.sqrt(Math.cbrt(Math.max(0.05D, yieldKt) / 20.0D));
	}

	/** Effective yield in kilotonnes after master multiplier + safety cap. */
	public double effectiveYieldKt(DoomsdayConfig c) {
		double mult = Math.max(0.001D, c.yieldMultiplier);
		DoomsdayConfig.DeviceTuning t = c.devices.get(configKey);
		double base = t != null && t.yieldKt != null
			? Math.max(0.05D, t.yieldKt)
			: baseYieldKt * mult;
		return Math.min(base, Math.max(0.5D, c.maxEffectiveYieldKt));
	}

	/**
	 * Folds preset + config + per-device override into the numbers the simulation
	 * actually uses. Called from {@link ConfigManager} at load time and cached, so
	 * this is not a hot path.
	 */
	public ConfigManager.ResolvedTuning deriveTuning(DoomsdayConfig c, DoomsdayConfig.DeviceTuning t) {
		double yield = effectiveYieldKt(c);
		double geom = geometryScale(yield / Math.max(0.05D, baseYieldKt));
		double time = timeScale(yield / Math.max(0.05D, baseYieldKt));

		double blast = pick(t == null ? null : t.blastRadius, baseBlastRadius * geom * c.blastRadiusMultiplier);
		double fire = pick(t == null ? null : t.fireballRadius, baseFireballRadius * geom * c.fireballRadiusMultiplier);
		double wave = pick(t == null ? null : t.shockwaveRadius,
			Math.max(c.shockwaveRadius, baseShockwaveRadius * geom * c.shockwaveRadiusMultiplier));
		double crater = pick(t == null ? null : t.craterRadius,
			c.craterEnabled ? baseCraterRadius * geom * c.craterSizeMultiplier : 0.0D);
		double cloud = pick(t == null ? null : t.cloudScale, baseCloudScale * geom * c.cloudScaleMultiplier);
		double rad = pick(t == null ? null : t.radiationSeconds,
			c.radiationEnabled ? baseRadiationSeconds * time * c.radiationDurationMultiplier : 0.0D);

		return new ConfigManager.ResolvedTuning(
			yield,
			nonNeg(blast), nonNeg(fire), nonNeg(wave), Math.max(0.0D, crater),
			Math.max(0.05D, cloud), Math.max(0.0D, rad));
	}

	private static double pick(Double override, double derived) {
		return override != null ? nonNeg(override) : derived;
	}

	private static double nonNeg(double v) {
		if (Double.isNaN(v) || Double.isInfinite(v)) {
			return 0.0D;
		}
		return Math.max(0.0D, v);
	}

	/** Lookup used by packets/registry code; never throws. */
	public static NukePreset byOrdinalOrFallback(int ordinal) {
		NukePreset[] all = values();
		return ordinal >= 0 && ordinal < all.length ? all[ordinal] : STANDARD_NUKE;
	}

	public static NukePreset byConfigKey(String key) {
		for (NukePreset p : values()) {
			if (p.configKey.equals(key)) {
				return p;
			}
		}
		return STANDARD_NUKE;
	}
}
