package com.doomsday.nukes.config;

import java.util.EnumMap;
import java.util.Map;

/**
 * Every tunable in Doomsday Nukes. This is a plain mutable POJO that is Gson
 * (de)serialised to {@code config/doomsday.json}; it intentionally contains no
 * Minecraft types so it can be read on a dedicated server, written from the config
 * screen, and diffed by users.
 *
 * <p><b>Units convention</b>: everything time-like is in <em>seconds</em> (floats),
 * everything distance-like is in <em>blocks</em>, everything yield-like is in
 * <em>kilotonnes of TNT equivalent</em>. Internally, time is converted to ticks with
 * {@code (int) Math.round(seconds * 20)} at exactly one place
 * ({@code DoomsdayConfig#ticks(float)}) so rounding behaviour is uniform.</p>
 *
 * <p>Defaults are tuned so the mod is spectacular out of the box while a 100 kt
 * Tsar Bomba stays playable (see {@code performance} and {@code vfx}).</p>
 */
public final class DoomsdayConfig {
	public static final int CURRENT_VERSION = 3;

	public int configVersion = CURRENT_VERSION;

	// ═══════════════════════════════════════ MASTER / YIELDS ═══════════════════════════════════════
	/** Multiplies the yield of every device. 1.0 = design values. */
	public double yieldMultiplier = 1.0D;
	/** Multiplies blast (destructive) radius independently of yield. */
	public double blastRadiusMultiplier = 1.0D;
	/** Multiplies the visual fireball radius. */
	public double fireballRadiusMultiplier = 1.0D;
	/** Multiplies the condensation-wave radius. */
	public double shockwaveRadiusMultiplier = 1.0D;
	/** Multiplies the mushroom cloud scale (stem + cap + skirt). */
	public double cloudScaleMultiplier = 1.0D;
	/** Multiplies how long radiation stays dangerous in blocks/area, not duration. */
	public double contaminationRadiusMultiplier = 1.0D;
	/** Multiplies every radiation/contamination duration. */
	public double radiationDurationMultiplier = 1.0D;
	/** Absolute cap (kilotonnes) applied after yieldMultiplier — the safety valve. */
	public double maxEffectiveYieldKt = 200.0D;

	/** Per-device overrides; keys are {@code NukePreset#name()} lowercase. */
	public Map<String, DeviceTuning> devices = DeviceTuning.defaults();

	// ═══════════════════════════════════════ STAGE 0 — ARMING ═══════════════════════════════════════
	public int minTimerSeconds = 10;
	public int maxTimerSeconds = 300;
	public int defaultTimerSeconds = 30;
	/** Villager/mob panic radius while armed (blocks). 0 disables the reaction. */
	public double mobPanicRadius = 32.0D;
	/** How often (ticks) the arming reaction may re-scan; throttles AI churn. */
	public int mobReactionIntervalTicks = 40;
	/** Max entities affected per device per scan — hard bound, never removed. */
	public int mobReactionMaxEntities = 48;
	/** Allow players to disarm a live device by sneaking + interacting. */
	public boolean allowDisarm = true;
	/** Remote detonator may cancel/re-arm a device, not just fire it. */
	public boolean remoteCanDisarm = true;

	// ═══════════════════════════════════════ STAGE 1 — FLASH ═══════════════════════════════════════
	public boolean flashEnabled = true;
	/** Distance at which the flash is still clearly visible; falloff beyond is smooth. */
	public double flashDistance = 500.0D;
	/** Beyond flashDistance the flash continues to {@code flashDistance * flashTailScale}. */
	public double flashTailScale = 6.0D;
	/** Attenuation exponent of the physically-inspired curve (2 = inverse square). */
	public double flashFalloffExponent = 2.0D;
	/** Phase A: pure whiteout length. 0.20 s as specified; do not set > 1.0 (photosensitive). */
	public float flashWhiteoutSeconds = 0.20F;
	/** Phase B: total white -> desaturated -> normal duration. */
	public float flashDesaturateSeconds = 2.80F;
	/** Peak bloom/exposure multiplier at the flash apex. */
	public double flashBloom = 1.85D;
	/** Seconds of Blindness without goggles (0 disables the gameplay penalty). */
	public int flashBlindnessSeconds = 6;
	/** Seconds of Blindness with Hazmat Goggles on (greatly reduced, not zero). */
	public int flashBlindnessGoggledSeconds = 1;
	/** Distance past which no blindness is applied (blocks). */
	public double flashBlindnessRadius = 260.0D;
	public boolean chromaticAberration = true;
	public double chromaticStrength = 1.0D;
	public boolean lensDirt = true;
	/** Lens dirt is only drawn above this normalised intensity — keeps quiet scenes clean. */
	public double lensDirtThreshold = 0.35D;
	public boolean vignette = true;
	public double vignetteStrength = 0.55D;
	public boolean exposureAnimation = true;
	/** FOV punch target in degrees during the shockwave (70 = vanilla, no punch). */
	public double flashFovDegrees = 110.0D;
	public boolean fovPunchEnabled = true;
	/** Duration of the temporary epicentre light (seconds). */
	public float flashLightSeconds = 1.0F;
	/** Use the emissive light block fallback (works with Sodium, no dynamic-light mod needed). */
	public boolean flashLightFallback = true;

	// ═══════════════════════════════════════ STAGE 2 — FIREBALL ═══════════════════════════════════════
	/** Radius in blocks the fireball grows TO at yield 1.0 for a Standard Nuke. */
	public double fireballRadius = 26.0D;
	/** Radius it starts at (blocks). */
	public double fireballStartRadius = 5.0D;
	public float fireballGrowSeconds = 2.0F;
	public float fireballHoldSeconds = 1.4F;
	public float fireballFadeSeconds = 4.6F;
	public boolean heatHaze = true;
	/** Heat-haze screen distortion strength at the fireball silhouette. */
	public double heatHazeStrength = 1.0D;
	/** Convert sand->glass / water->steam / burn flammables inside the fireball. */
	public boolean fireballTerrifiesTerrain = true;

	// ═══════════════════════════════════════ STAGE 3 — SHOCKWAVE ═══════════════════════════════════════
	/** Visible condensation wave outer radius in blocks (0 for "derive from yield"). */
	public double shockwaveRadius = 0.0D;
	/** Expansion speed, blocks/second. */
	public double shockwaveSpeed = 300.0D;
	public float shockwaveDurationSeconds = 4.0F;
	public int shockwaveRingCount = 3;
	/** Destructive wave: destroys weak blocks, strips leaves, knocks back entities. */
	public boolean shockwaveDestructive = true;
	/** Peak knockback velocity (blocks/tick) at the wavefront for yield 1.0. */
	public double knockbackPeak = 2.4D;
	/** Distance clamp used by force = yield / max(d^2, minDist^2). */
	public double knockbackMinDistance = 6.0D;
	/** Vertical launch bonus as a fraction of horizontal impulse. */
	public double knockbackLift = 0.42D;
	/** Damage at the wavefront for yield 1.0, halves with distance². */
	public double shockwaveDamage = 9.0D;
	public double cameraShakeStrength = 1.0D;
	/** Duration of the intense shake window for a close observer (seconds). */
	public float cameraShakeSeconds = 2.0F;
	/** Distance under which the shake is at full strength. */
	public double cameraShakeFullRadius = 48.0D;
	/** Distance past which shake is inaudible/imperceptible. */
	public double cameraShakeMaxRadius = 900.0D;
	/** Roll amplitude (degrees) at full strength. */
	public double cameraShakeRollDegrees = 3.4D;
	/** Screen-space shake amplitude in blocks at full strength. */
	public double cameraShakeAmplitude = 0.34D;

	// ═══════════════════════════════════════ STAGE 4 — MUSHROOM CLOUD ══════════════════════════════════
	public boolean cloudEnabled = true;
	public float cloudLifetimeSeconds = 90.0F;
	/** Total rise over the lifetime (blocks). */
	public double cloudRiseBlocks = 150.0D;
	/** Segments in the stem (spec target 20). */
	public int cloudStemSegments = 20;
	/** Logical samples in the cap (spec target 100). */
	public int cloudCapSamples = 100;
	/** Logical samples in the skirt. */
	public int cloudSkirtSamples = 28;
	/** Max distance the cloud remains *drawn* at (blocks). Spec target: 3000+. */
	public double cloudVisibilityDistance = 3400.0D;
	/** Distance past which the cap/skirt collapse to a cheap billboard silhouette. */
	public double cloudLodNearDistance = 220.0D;
	public double cloudLodFarDistance = 900.0D;
	public boolean cloudEmissiveAtNight = true;
	/** Optional ground shadow — screen-space only, never touches chunk lighting. */
	public boolean cloudShadow = true;
	/** Boiling (turbulence) strength of the cloud surface. */
	public double cloudTurbulence = 1.0D;
	/** Base cap radius in blocks at yield 1.0. */
	public double cloudCapRadius = 74.0D;

	// ═══════════════════════════════════════ STAGE 5 — CRATER / WORLD ════════════════════════════════
	public boolean craterEnabled = true;
	public double craterSizeMultiplier = 1.0D;
	/** Master switch for ALL block destruction. Off = visual-only "clean" mode. */
	public boolean griefingEnabled = true;
	/** Blocks written per tick per detonation. The single most important lag knob. */
	public int terrainBlocksPerTick = 900;
	/** Max ticks a single detonation may spend on terrain; work is dropped after. */
	public int terrainMaxTicks = 260;
	/** Radius past the crater where blocks are only scorched, never removed. */
	public double scorchBandBlocks = 12.0D;
	/** Vitrify crater floor into trinitite glass. */
	public boolean vitrifyCraterFloor = true;
	/** Replace water in the fireball with air + steam particles. */
	public boolean evaporateWater = true;
	/** Set fire to flammable blocks near the fireball edge. */
	public boolean igniteFlammables = true;
	/** Restore "heated stone" back to plain stone after this many seconds. */
	public float heatedStoneSeconds = 45.0F;
	/** Chunk work: never touch more than this many chunks per tick (bounded streaming). */
	public int terrainMaxChunksPerTick = 12;

	// ═══════════════════════════════════════ STAGE 5 — FALLOUT / ATMOSPHERE ═══════════════════════════
	public boolean falloutEnabled = true;
	/** How long ash keeps raining (seconds). Spec target ~180 s. */
	public float ashFallSeconds = 180.0F;
	public double falloutSpreadBlocks = 240.0D;
	public int maxAshParticles = 2600;
	public boolean atmosphericAftermath = true;
	/** Sky darkening at the apex (0..1). */
	public double skyDarkness = 0.72D;
	/** Seconds for the atmosphere to return to normal after the apex. */
	public float atmosphereRecoverSeconds = 210.0F;
	public double fogOrangeMix = 0.55D;
	/** Extra fog density applied under the cloud (world units, additive). */
	public double hazeDensity = 0.021D;
	/** Keep the cloud visible through the haze (aesthetic, not a lighting change). */
	public boolean distantCloudThroughHaze = true;

	// ═══════════════════════════════════════ RADIATION ════════════════════════════════════════════════
	public boolean radiationEnabled = true;
	/** Base radiation dose rate multiplier for standing in contamination. */
	public double radiationIntensity = 1.0D;
	/** Seconds of Radiation Sickness per contamination "level" of exposure. */
	public float radiationEffectSeconds = 40.0F;
	/** Damage per radiation tick (half-hearts = 1.0). */
	public float radiationDamagePerTick = 1.0F;
	public int radiationTickIntervalSeconds = 4;
	/** Maximum stack of Radiation Sickness (severity cap, prevents death spirals). */
	public int radiationMaxAmplifier = 4;
	/** Hazmat Goggles: 0.2 = 80 % resistance, exactly as specified. */
	public double goggleRadiationMultiplier = 0.2D;
	/** Apply WEAKNESS + MINING_FATIGUE alongside the custom effect. */
	public boolean radiationAppliesVanillaDebuffs = true;
	/** Iodine tablets: seconds of protection after use. */
	public float iodineProtectionSeconds = 90.0F;
	/** Iodine tablets: reduction factor applied to the sickness amplifier. */
	public double iodineAmplifierReduction = 2.0D;
	/** Drinking milk clears radiation (vanilla milk behaviour, kept configurable). */
	public boolean milkCuresRadiation = true;
	/** Green screen vignette strength while irradiated. */
	public double radiationVignette = 0.65D;
	/** Contamination decays by this fraction per minute (0 = permanent until chunk unload). */
	public double contaminationDecayPerMinute = 0.06D;

	// ═══════════════════════════════════════ EMP ═══════════════════════════════════════════════════════
	public boolean empEnabled = true;
	public float empSeconds = 10.0F;
	public double empRadiusMultiplier = 1.6D;
	/** Redstone lamps flicker while EMP is active. */
	public boolean empFlickersLamps = true;
	/** Redstone torches / wires are forced off, then restored from the captured state. */
	public boolean empDisablesRedstone = true;
	/** Beacons are suspended (best-effort, degrades to no-op if unsupported). */
	public boolean empDisablesBeacons = true;
	/** Max blocks inspected per EMP zone per tick — the anti-lag bound. */
	public int empBlocksPerTick = 1200;
	/** Hard cap on how far the EMP scan reaches, regardless of yield (blocks). */
	public double empMaxRadius = 120.0D;
	/** Column stride of the bounded scan. 1 = every block (expensive), 3 = default. */
	public int empScanStride = 3;
	/** How many blocks below the surface column top the scan looks. */
	public int empSurfaceDepth = 14;
	/** Probability per visit that a lamp flickers (0..1). */
	public double empLampFlickerChance = 0.55D;

	// ═══════════════════════════════════════ SOUNDS ═══════════════════════════════════════════════════
	/** Distance at which the detonation is still audible (blocks). */
	public double soundDistance = 900.0D;
	/** Delay the boom by distance/sound-speed for the cinematic "far boom". */
	public boolean distantBoomDelay = true;
	/** Metres per second used for the boom delay (343 default). */
	public double soundSpeed = 343.0D;
	public double masterVolume = 1.0D;
	public double sirenVolume = 1.0D;
	/** Minimum ticks between Geiger clicks at maximum intensity (pooling bound). */
	public int geigerMinClickIntervalTicks = 2;
	/** Geiger counter audible reach for *other* players (blocks). 0 = self only. */
	public double geigerRange = 96.0D;
	/** Geiger base sweep: clicks per second at radiation level 1 and 0. */
	public double geigerClicksPerSecondLow = 1.2D;
	public double geigerClicksPerSecondHigh = 22.0D;

	// ═══════════════════════════════════════ HUD ══════════════════════════════════════════════════════
	public boolean hudEnabled = true;
	public boolean showArmedBanner = true;
	public boolean showGeigerGauge = true;
	public boolean showRadiationVignette = true;
	public boolean showContaminationCompass = true;
	/** Top-left corner offset so the gauge does not fight other mods' HUDs. */
	public int hudOffsetX = 0;
	public int hudOffsetY = 0;
	/** HUD scale (0.5 .. 2.0). */
	public double hudScale = 1.0D;

	// ═══════════════════════════════════════ COMPAT ═══════════════════════════════════════════════════
	/** Skip all custom rendering when Iris shaders are active (they replace the FB chain). */
	public boolean disablePostWhenIrisActive = true;
	/** Render through vanilla entity/BER passes instead of a world-render layer of our own. */
	public boolean preferSodiumSafePaths = true;
	/** Log one line per stage transition — for diagnosing client/server drift. */
	public boolean verboseLogging = false;

	// ═══════════════════════════════════════ VFX / PERFORMANCE ═════════════════════════════════════════
	/** Master VFX ceiling. Effects are scaled DOWN to this, never forced up. */
	public Quality quality = Quality.HIGH;
	/** 0.25 .. 1.0 — fraction of the quality budget actually used. */
	public double particleDensity = 0.85D;
	/** Hard cap on concurrent mod particles across every effect (pooled). */
	public int maxParticles = 6000;
	/** Cap on concurrent client-side visual entities (fireball/shockwave/cloud/etc.). */
	public int maxVisualEntities = 24;
	/** Cap on simultaneous detonations simulated; excess are merged into the nearest one. */
	public int maxConcurrentDetonations = 6;
	/** Distance past which a detonation's client visuals degrade to silhouettes only. */
	public double visualLodDistance = 640.0D;
	/** Disable all particle work past this distance from the epicentre. */
	public double particleCullDistance = 420.0D;
	/** Ticks between client-side LOD re-evaluations. */
	public int lodUpdateIntervalTicks = 5;
	/** Batched particle quads per draw call group. */
	public int particleBatchSize = 512;
	/** Freeze new particle spawns when the client frame time exceeds this (ms). */
	public double adaptiveBudgetMs = 33.0D;
	/** Automatically reduce particleDensity when frame budget is missed. */
	public boolean adaptiveQuality = true;
	/** Max chunks the crater system may force-load in one go (safety). */
	public int maxChunksTouched = 64;

	// ═══════════════════════════════════════ INTERNAL ═════════════════════════════════════════════════
	/** Runtime-only (never serialised by intent, defaults to false on load). */
	public transient boolean loadedFromDisk = false;

	public static int ticks(float seconds) {
		return Math.max(0, (int) Math.round(seconds * 20.0F));
	}

	public static float seconds(int ticks) {
		return ticks / 20.0F;
	}

	/** Quality tiers. Each carries concrete budgets rather than vague labels. */
	public enum Quality {
		/** Everything off except the bare minimum needed for gameplay clarity. */
		LOW(1400, 0.34D, 12, 1),
		/** Balanced: default target for mid-range GPUs. */
		MEDIUM(3200, 0.6D, 20, 2),
		/** Full spec visual set. */
		HIGH(6000, 1.0D, 28, 3),
		/** Unleashed — still bounded, but every layer is at max sample counts. */
		ULTRA(11000, 1.0D, 40, 4);

		public final int particleBudget;
		public final double sampleScale;
		public final int cloudCapSamples;
		/** Number of noise octaves evaluated in the cloud/fireball shaders. */
		public final int noiseOctaves;

		Quality(int particleBudget, double sampleScale, int cloudCapSamples, int noiseOctaves) {
			this.particleBudget = particleBudget;
			this.sampleScale = sampleScale;
			this.cloudCapSamples = cloudCapSamples;
			this.noiseOctaves = noiseOctaves;
		}

		public Quality clampTo(Quality ceiling) {
			return ordinal() > ceiling.ordinal() ? ceiling : this;
		}
	}

	/** Per-device override. Null/absent fields fall back to the preset's design values. */
	public static final class DeviceTuning {
		public Double yieldKt;
		public Double blastRadius;
		public Double fireballRadius;
		public Double shockwaveRadius;
		public Double craterRadius;
		public Double cloudScale;
		public Double radiationSeconds;

		public static Map<String, DeviceTuning> defaults() {
			Map<String, DeviceTuning> map = new java.util.LinkedHashMap<>();
			for (com.doomsday.nukes.detonation.NukePreset p : com.doomsday.nukes.detonation.NukePreset.values()) {
				map.put(p.configKey, new DeviceTuning());
			}
			return map;
		}

		/** Enum-keyed view used on hot paths to avoid string hashing per detonation. */
		public static Map<com.doomsday.nukes.detonation.NukePreset, DeviceTuning> index(DoomsdayConfig config) {
			Map<com.doomsday.nukes.detonation.NukePreset, DeviceTuning> out =
				new EnumMap<>(com.doomsday.nukes.detonation.NukePreset.class);
			for (com.doomsday.nukes.detonation.NukePreset p : com.doomsday.nukes.detonation.NukePreset.values()) {
				DeviceTuning t = config.devices.get(p.configKey);
				out.put(p, t == null ? new DeviceTuning() : t);
			}
			return out;
		}
	}
}
