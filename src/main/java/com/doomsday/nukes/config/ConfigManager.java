package com.doomsday.nukes.config;

import com.doomsday.nukes.DoomsdayNukes;
import com.doomsday.nukes.detonation.NukePreset;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Load / validate / save for {@link DoomsdayConfig}.
 *
 * <p>Guarantees the mod relies on:</p>
 * <ul>
 *   <li>{@link #get()} <b>never</b> returns null and never returns out-of-range values:
 *       every field is clamped in {@link #sanitize(DoomsdayConfig)} immediately after
 *       deserialisation, so a hand-edited or corrupt file cannot produce a negative
 *       radius, a NaN yield or an unbounded particle count.</li>
 *   <li>Missing file → defaults are written to disk so users have something to edit.</li>
 *   <li>{@code configVersion} mismatch → unknown/missing keys are merged from defaults
 *       instead of discarding the user's file (non-destructive migration).</li>
 *   <li>Derived per-preset values are cached in {@link #tuning}, so the detonation path
 *       never does string-keyed map lookups while the world is being destroyed.</li>
 * </ul>
 */
public final class ConfigManager {
	private static final Gson GSON = new GsonBuilder()
		.setPrettyPrinting()
		.serializeNulls()
		.disableHtmlEscaping()
		.create();

	private static final Path FILE =
		FabricLoader.getInstance().getConfigDir().resolve("doomsday.json");

	private static volatile DoomsdayConfig config = new DoomsdayConfig();
	private static Map<NukePreset, ResolvedTuning> tuning = resolve(new DoomsdayConfig());

	private ConfigManager() {
	}

	public static DoomsdayConfig get() {
		return config;
	}

	/** Fully-resolved, config-aware parameters for one device preset. */
	public record ResolvedTuning(
		double yieldKt,
		double blastRadius,
		double fireballRadius,
		double shockwaveRadius,
		double craterRadius,
		double cloudScale,
		double radiationSeconds
	) {
	}

	public static ResolvedTuning tuning(NukePreset preset) {
		ResolvedTuning t = tuning.get(preset);
		// Defensive: an unloaded/foreign preset still yields a sane, bounded profile.
		return t != null ? t : preset.deriveTuning(config, new DoomsdayConfig.DeviceTuning());
	}

	// ——————————————————————————————————————————————————————— lifecycle

	public static synchronized void load() {
		if (!Files.exists(FILE)) {
			config = new DoomsdayConfig();
			sanitize(config);
			save();
			DoomsdayNukes.LOGGER.info("Wrote default configuration to {}", FILE);
			tuning = resolve(config);
			return;
		}
		DoomsdayConfig loaded = null;
		try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
			loaded = GSON.fromJson(reader, DoomsdayConfig.class);
		} catch (IOException | RuntimeException e) {
			DoomsdayNukes.LOGGER.error(
				"Could not read {}; falling back to defaults for this session. File left untouched.",
				FILE, e);
		}
		if (loaded == null) {
			// Gson returns null for a literally empty file.
			loaded = new DoomsdayConfig();
		}
		mergeDefaults(loaded);
		sanitize(loaded);
		loaded.loadedFromDisk = true;
		config = loaded;
		tuning = resolve(config);
		DoomsdayNukes.LOGGER.info(
			"Configuration v{} loaded ({} device overrides, quality={}, yieldMultiplier={})",
			config.configVersion, countOverrides(config), config.quality, config.yieldMultiplier);
	}

	private static int countOverrides(DoomsdayConfig c) {
		int n = 0;
		for (DoomsdayConfig.DeviceTuning t : c.devices.values()) {
			if (t.yieldKt != null || t.blastRadius != null || t.fireballRadius != null
				|| t.shockwaveRadius != null || t.craterRadius != null || t.cloudScale != null
				|| t.radiationSeconds != null) {
				n++;
			}
		}
		return n;
	}

	public static synchronized void save() {
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
				GSON.toJson(config, writer);
			}
		} catch (IOException e) {
			DoomsdayNukes.LOGGER.error("Could not write {}", FILE, e);
		}
	}

	public static synchronized void resetToDefaults() {
		config = new DoomsdayConfig();
		sanitize(config);
		tuning = resolve(config);
		save();
	}

	/** Called by the config screen after a live edit. */
	public static synchronized void applyAndSave(DoomsdayConfig next) {
		config = next;
		sanitize(config);
		tuning = resolve(config);
		save();
	}

	// ————————————————————————————————————————————————————— internals

	/** Re-adds any key a newer mod version introduced, keeping the user's other edits. */
	private static void mergeDefaults(DoomsdayConfig loaded) {
		DoomsdayConfig defaults = new DoomsdayConfig();
		if (loaded.devices == null) {
			loaded.devices = new LinkedHashMap<>();
		}
		for (NukePreset p : NukePreset.values()) {
			loaded.devices.computeIfAbsent(p.configKey, k -> new DoomsdayConfig.DeviceTuning());
		}
		if (loaded.quality == null) {
			loaded.quality = defaults.quality;
		}
		if (loaded.configVersion <= 0) {
			loaded.configVersion = DoomsdayConfig.CURRENT_VERSION;
		}
	}

	private static Map<NukePreset, ResolvedTuning> resolve(DoomsdayConfig c) {
		Map<NukePreset, ResolvedTuning> out = new EnumMap<>(NukePreset.class);
		for (NukePreset p : NukePreset.values()) {
			DoomsdayConfig.DeviceTuning t = c.devices.get(p.configKey);
			out.put(p, p.deriveTuning(c, t == null ? new DoomsdayConfig.DeviceTuning() : t));
		}
		return out;
	}

	/**
	 * Clamp every field into a range that is safe for both simulation and rendering.
	 * Ranges here are the documented contract for the config screen's steppers.
	 */
	private static void sanitize(DoomsdayConfig c) {
		c.yieldMultiplier = clampd(c.yieldMultiplier, 0.05D, 8.0D);
		c.blastRadiusMultiplier = clampd(c.blastRadiusMultiplier, 0.0D, 6.0D);
		c.fireballRadiusMultiplier = clampd(c.fireballRadiusMultiplier, 0.05D, 6.0D);
		c.shockwaveRadiusMultiplier = clampd(c.shockwaveRadiusMultiplier, 0.05D, 8.0D);
		c.cloudScaleMultiplier = clampd(c.cloudScaleMultiplier, 0.1D, 6.0D);
		c.contaminationRadiusMultiplier = clampd(c.contaminationRadiusMultiplier, 0.0D, 6.0D);
		c.radiationDurationMultiplier = clampd(c.radiationDurationMultiplier, 0.0D, 12.0D);
		c.maxEffectiveYieldKt = clampd(c.maxEffectiveYieldKt, 0.5D, 2000.0D);

		c.minTimerSeconds = clampi(c.minTimerSeconds, 1, 60);
		c.maxTimerSeconds = Math.max(c.minTimerSeconds, clampi(c.maxTimerSeconds, c.minTimerSeconds, 1800));
		c.defaultTimerSeconds = clampi(c.defaultTimerSeconds, c.minTimerSeconds, c.maxTimerSeconds);
		c.mobPanicRadius = clampd(c.mobPanicRadius, 0.0D, 128.0D);
		c.mobReactionIntervalTicks = clampi(c.mobReactionIntervalTicks, 5, 400);
		c.mobReactionMaxEntities = clampi(c.mobReactionMaxEntities, 0, 256);

		c.flashDistance = clampd(c.flashDistance, 16.0D, 20000.0D);
		c.flashTailScale = clampd(c.flashTailScale, 1.0D, 32.0D);
		c.flashFalloffExponent = clampd(c.flashFalloffExponent, 0.5D, 6.0D);
		c.flashWhiteoutSeconds = clampf(c.flashWhiteoutSeconds, 0.0F, 1.0F);
		c.flashDesaturateSeconds = clampf(c.flashDesaturateSeconds, 0.2F, 12.0F);
		c.flashBloom = clampd(c.flashBloom, 0.0D, 6.0D);
		c.flashBlindnessSeconds = clampi(c.flashBlindnessSeconds, 0, 60);
		c.flashBlindnessGoggledSeconds = clampi(c.flashBlindnessGoggledSeconds, 0, c.flashBlindnessSeconds);
		c.flashBlindnessRadius = clampd(c.flashBlindnessRadius, 8.0D, 4000.0D);
		c.chromaticStrength = clampd(c.chromaticStrength, 0.0D, 4.0D);
		c.lensDirtThreshold = clampd(c.lensDirtThreshold, 0.0D, 1.0D);
		c.vignetteStrength = clampd(c.vignetteStrength, 0.0D, 1.0D);
		c.flashFovDegrees = clampd(c.flashFovDegrees, 60.0D, 150.0D);
		c.flashLightSeconds = clampf(c.flashLightSeconds, 0.05F, 20.0F);

		c.fireballRadius = clampd(c.fireballRadius, 2.0D, 400.0D);
		c.fireballStartRadius = Math.min(c.fireballRadius, clampd(c.fireballStartRadius, 0.5D, 200.0D));
		c.fireballGrowSeconds = clampf(c.fireballGrowSeconds, 0.1F, 30.0F);
		c.fireballHoldSeconds = clampf(c.fireballHoldSeconds, 0.0F, 30.0F);
		c.fireballFadeSeconds = clampf(c.fireballFadeSeconds, 0.1F, 60.0F);
		c.heatHazeStrength = clampd(c.heatHazeStrength, 0.0D, 4.0D);

		c.shockwaveRadius = clampd(c.shockwaveRadius, 0.0D, 4000.0D);
		c.shockwaveSpeed = clampd(c.shockwaveSpeed, 5.0D, 2000.0D);
		c.shockwaveDurationSeconds = clampf(c.shockwaveDurationSeconds, 0.25F, 40.0F);
		c.shockwaveRingCount = clampi(c.shockwaveRingCount, 1, 6);
		c.knockbackPeak = clampd(c.knockbackPeak, 0.0D, 12.0D);
		c.knockbackMinDistance = clampd(c.knockbackMinDistance, 0.5D, 128.0D);
		c.knockbackLift = clampd(c.knockbackLift, 0.0D, 2.0D);
		c.shockwaveDamage = clampd(c.shockwaveDamage, 0.0D, 400.0D);
		c.cameraShakeStrength = clampd(c.cameraShakeStrength, 0.0D, 4.0D);
		c.cameraShakeSeconds = clampf(c.cameraShakeSeconds, 0.0F, 20.0F);
		c.cameraShakeFullRadius = clampd(c.cameraShakeFullRadius, 1.0D, 512.0D);
		c.cameraShakeMaxRadius = Math.max(c.cameraShakeFullRadius * 2.0D, clampd(c.cameraShakeMaxRadius, 8.0D, 12000.0D));
		c.cameraShakeRollDegrees = clampd(c.cameraShakeRollDegrees, 0.0D, 25.0D);
		c.cameraShakeAmplitude = clampd(c.cameraShakeAmplitude, 0.0D, 3.0D);

		c.cloudLifetimeSeconds = clampf(c.cloudLifetimeSeconds, 2.0F, 1800.0F);
		c.cloudRiseBlocks = clampd(c.cloudRiseBlocks, 0.0D, 1200.0D);
		c.cloudStemSegments = clampi(c.cloudStemSegments, 4, 64);
		c.cloudCapSamples = clampi(c.cloudCapSamples, 8, 400);
		c.cloudSkirtSamples = clampi(c.cloudSkirtSamples, 4, 200);
		c.cloudVisibilityDistance = clampd(c.cloudVisibilityDistance, 64.0D, 40000.0D);
		c.cloudLodNearDistance = clampd(c.cloudLodNearDistance, 16.0D, 4000.0D);
		c.cloudLodFarDistance = Math.max(c.cloudLodNearDistance * 1.5D, clampd(c.cloudLodFarDistance, 32.0D, 12000.0D));
		c.cloudTurbulence = clampd(c.cloudTurbulence, 0.0D, 4.0D);
		c.cloudCapRadius = clampd(c.cloudCapRadius, 4.0D, 900.0D);

		c.craterSizeMultiplier = clampd(c.craterSizeMultiplier, 0.0D, 6.0D);
		c.terrainBlocksPerTick = clampi(c.terrainBlocksPerTick, 60, 8000);
		c.terrainMaxTicks = clampi(c.terrainMaxTicks, 10, 4000);
		c.scorchBandBlocks = clampd(c.scorchBandBlocks, 0.0D, 128.0D);
		c.heatedStoneSeconds = clampf(c.heatedStoneSeconds, 1.0F, 3600.0F);
		c.terrainMaxChunksPerTick = clampi(c.terrainMaxChunksPerTick, 1, 96);
		c.maxChunksTouched = clampi(c.maxChunksTouched, 1, 512);

		c.ashFallSeconds = clampf(c.ashFallSeconds, 1.0F, 3600.0F);
		c.falloutSpreadBlocks = clampd(c.falloutSpreadBlocks, 4.0D, 2000.0D);
		c.maxAshParticles = clampi(c.maxAshParticles, 32, 20000);
		c.skyDarkness = clampd(c.skyDarkness, 0.0D, 1.0D);
		c.atmosphereRecoverSeconds = clampf(c.atmosphereRecoverSeconds, 1.0F, 3600.0F);
		c.fogOrangeMix = clampd(c.fogOrangeMix, 0.0D, 1.0D);
		c.hazeDensity = clampd(c.hazeDensity, 0.0D, 0.25D);

		c.radiationIntensity = clampd(c.radiationIntensity, 0.0D, 8.0D);
		c.radiationEffectSeconds = clampf(c.radiationEffectSeconds, 1.0F, 3600.0F);
		c.radiationDamagePerTick = clampf(c.radiationDamagePerTick, 0.0F, 20.0F);
		c.radiationTickIntervalSeconds = clampi(c.radiationTickIntervalSeconds, 1, 60);
		c.radiationMaxAmplifier = clampi(c.radiationMaxAmplifier, 0, 10);
		c.goggleRadiationMultiplier = clampd(c.goggleRadiationMultiplier, 0.0D, 1.0D);
		c.iodineProtectionSeconds = clampf(c.iodineProtectionSeconds, 0.0F, 3600.0F);
		c.iodineAmplifierReduction = clampd(c.iodineAmplifierReduction, 0.0D, 10.0D);
		c.radiationVignette = clampd(c.radiationVignette, 0.0D, 1.0D);
		c.contaminationDecayPerMinute = clampd(c.contaminationDecayPerMinute, 0.0D, 1.0D);

		c.empSeconds = clampf(c.empSeconds, 0.5F, 600.0F);
		c.empRadiusMultiplier = clampd(c.empRadiusMultiplier, 0.1D, 12.0D);
		c.empBlocksPerTick = clampi(c.empBlocksPerTick, 60, 20000);

		c.soundDistance = clampd(c.soundDistance, 16.0D, 20000.0D);
		c.soundSpeed = clampd(c.soundSpeed, 50.0D, 1000.0D);
		c.masterVolume = clampd(c.masterVolume, 0.0D, 4.0D);
		c.sirenVolume = clampd(c.sirenVolume, 0.0D, 4.0D);
		c.geigerMinClickIntervalTicks = clampi(c.geigerMinClickIntervalTicks, 1, 100);
		c.geigerRange = clampd(c.geigerRange, 8.0D, 2048.0D);
		c.geigerClicksPerSecondLow = clampd(c.geigerClicksPerSecondLow, 0.0D, 20.0D);
		c.geigerClicksPerSecondHigh = Math.max(c.geigerClicksPerSecondLow, clampd(c.geigerClicksPerSecondHigh, 0.0D, 60.0D));

		c.hudOffsetX = clampi(c.hudOffsetX, -400, 400);
		c.hudOffsetY = clampi(c.hudOffsetY, -400, 400);
		c.hudScale = clampd(c.hudScale, 0.5D, 2.0D);

		c.maxParticles = clampi(c.maxParticles, 64, 40000);
		c.maxVisualEntities = clampi(c.maxVisualEntities, 1, 256);
		c.maxConcurrentDetonations = clampi(c.maxConcurrentDetonations, 1, 32);
		c.particleDensity = clampd(c.particleDensity, 0.05D, 1.0D);
		c.visualLodDistance = clampd(c.visualLodDistance, 32.0D, 20000.0D);
		c.particleCullDistance = clampd(c.particleCullDistance, 16.0D, 4000.0D);
		c.lodUpdateIntervalTicks = clampi(c.lodUpdateIntervalTicks, 1, 100);
		c.particleBatchSize = clampi(c.particleBatchSize, 32, 4096);
		c.adaptiveBudgetMs = clampd(c.adaptiveBudgetMs, 6.0D, 500.0D);

		// Yield safety cap must never fall below the smallest device.
		double biggest = 0.0D;
		for (NukePreset p : NukePreset.values()) {
			biggest = Math.max(biggest, p.baseYieldKt);
		}
		if (c.maxEffectiveYieldKt < biggest * 0.25D) {
			c.maxEffectiveYieldKt = Math.max(biggest, biggest * c.yieldMultiplier);
		}
	}

	private static double clampd(double v, double lo, double hi) {
		if (Double.isNaN(v) || Double.isInfinite(v)) {
			return lo;
		}
		return v < lo ? lo : (v > hi ? hi : v);
	}

	private static float clampf(float v, float lo, float hi) {
		if (Float.isNaN(v) || Float.isInfinite(v)) {
			return lo;
		}
		return v < lo ? lo : (v > hi ? hi : v);
	}

	private static int clampi(int v, int lo, int hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	/** Debug aid: stable dump used by {@code /doomsday dump}. */
	public static String describe() {
		StringBuilder sb = new StringBuilder(512);
		sb.append("Doomsday config v").append(config.configVersion)
			.append(" | quality=").append(config.quality)
			.append(" | yield x").append(String.format(java.util.Locale.ROOT, "%.2f", config.yieldMultiplier))
			.append(" | griefing=").append(config.griefingEnabled)
			.append(" | flash=").append(config.flashEnabled)
			.append(" | cloud=").append(config.cloudEnabled)
			.append(" | radiation=").append(config.radiationEnabled)
			.append(" | emp=").append(config.empEnabled)
			.append(" | fallout=").append(config.falloutEnabled)
			.append(" | terrain/tick=").append(config.terrainBlocksPerTick).append('\n');
		for (NukePreset p : NukePreset.values()) {
			ResolvedTuning t = tuning.get(p);
			sb.append("  ").append(p.configKey).append(": ")
				.append(String.format(java.util.Locale.ROOT, "%.1f kt", t.yieldKt()))
				.append(" blast=").append((int) t.blastRadius())
				.append(" fire=").append((int) t.fireballRadius())
				.append(" wave=").append((int) t.shockwaveRadius())
				.append(" crater=").append((int) t.craterRadius())
				.append(" rad=").append((int) t.radiationSeconds()).append('s')
				.append('\n');
		}
		return sb.toString();
	}
}
