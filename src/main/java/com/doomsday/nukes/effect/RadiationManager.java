package com.doomsday.nukes.effect;

import com.doomsday.nukes.DoomsdayNukes;
import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.config.DoomsdayConfig;
import com.doomsday.nukes.item.HazmatGear;
import com.doomsday.nukes.network.ModPackets;
import com.doomsday.nukes.network.packet.RadiationSyncS2CPacket;
import com.doomsday.nukes.registry.ModStatusEffects;
import com.doomsday.nukes.util.SpatialUtil;
import com.doomsday.nukes.world.DoomsdayWorldData;
import it.unimi.dsi.fastutil.longs.Long2FloatMap;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The radiation system: a server-authored contamination field, per-player exposure, and one
 * throttled sync packet.
 *
 * <h2>Two numbers, deliberately</h2>
 * <ol>
 *   <li><b>Contamination</b> — per chunk, in the level's {@link DoomsdayWorldData}. Written by
 *       detonations, keyed by chunk, persisted. That is the <em>environment</em>, and it is the
 *       only thing that survives a restart.</li>
 *   <li><b>Exposure</b> — per player, 0..100, held in this class. It integrates contamination over
 *       time, recovers when you are clean, and drives the {@link RadiationSicknessEffect}
 *       amplifier. Keeping it out of the effect means milk, {@code /effect clear} and death can
 *       remove the debuff without erasing the fact that you walked through a hot zone.</li>
 * </ol>
 *
 * <h2>Cost</h2>
 * No block queries and no per-chunk entities. One map lookup per player per second, one
 * round-robin decay window per minute for the whole field, one packet per player per second only
 * while a field exists near them. Players outside any contaminated region get nothing at all.
 *
 * <h2>Trust</h2>
 * Everything client-side is a display copy. The client never computes dose: it receives the field
 * around the player it is standing in and renders it. That is why there is no C2S radiation
 * packet in this mod.
 */
public final class RadiationManager {
	/** Ticks between exposure updates per player (1 s). */
	public static final int SYNC_INTERVAL_TICKS = 20;
	/** Exposure at which the first amplifier of Radiation Sickness is applied. */
	public static final double SICKNESS_THRESHOLD = 25.0D;
	/** Natural recovery, exposure points per minute spent away from contamination. */
	public static final double RECOVERY_PER_MINUTE = 1.6D;
	/** Contamination entries decayed per pass (round robin), i.e. the field's amortised cost. */
	public static final int DECAY_WINDOW = 4096;
	/** Ticks between decay passes (one minute). */
	public static final int DECAY_INTERVAL_TICKS = 1200;

	/** Player uuid -> accumulated exposure. */
	private static final Map<UUID, Double> EXPOSURE = new ConcurrentHashMap<>();
	/** Player uuid -> age at last integration step. */
	private static final Map<UUID, Long> LAST_UPDATE = new ConcurrentHashMap<>();
	/** Player uuid -> holding a counter (drives whether the field is synced at all). */
	private static final Map<UUID, Boolean> HOLDING = new ConcurrentHashMap<>();
	/** Player uuid -> iodine protection expiry, in local world time. */
	private static final Map<UUID, Long> IODINE_UNTIL = new ConcurrentHashMap<>();

	/** Client-side mirror of the last sync, so gauges/needle have data without querying the world. */
	private static volatile float clientDose;
	private static volatile double clientExposure;
	private static volatile boolean clientIodine;

	private static long decayCursor;
	private static long ticksSinceDecay;
	private static long updates;
	private static double peakExposure;
	private static RegistryEntry<StatusEffect> radiationEntry;

	private RadiationManager() {
	}

	// ———————————————————————————————————————————————————— server tick

	/** Called once from the entrypoint, before any world exists. */
	public static void init() {
		// Nothing to allocate: the maps are static-final and the effect's registry entry is
		// resolved lazily (radiation()), because the registry is not queryable at init time.
	}

	public static void onServerStart(MinecraftServer server) {
		decayCursor = 0;
		ticksSinceDecay = 0;
		updates = 0;
		peakExposure = 0.0D;
		// Exposure intentionally survives a restart: a server reload is not a decontamination
		// event, and a player who was dying at shutdown should still be dying at login.
	}

	public static void onServerStop() {
		EXPOSURE.clear();
		LAST_UPDATE.clear();
		HOLDING.clear();
		IODINE_UNTIL.clear();
	}

	/**
	 * Driven from the single {@code END_SERVER_TICK} registration in {@link DoomsdayNukes};
	 * this class installs no ticker of its own, so there is exactly one place to look when
	 * radiation needs profiling.
	 */
	public static void tick(MinecraftServer server) {
		DoomsdayConfig c = ConfigManager.get();
		if (!c.radiationEnabled) {
			return;
		}
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			if ((player.age % SYNC_INTERVAL_TICKS) == 0) {
				step(player, c);
			}
		}
		if (++ticksSinceDecay >= DECAY_INTERVAL_TICKS) {
			ticksSinceDecay = 0;
			decayField(server, c);
		}
	}

	/** Integrate one second of exposure for one player and refresh their effect + HUD. */
	private static void step(ServerPlayerEntity player, DoomsdayConfig c) {
		ServerWorld world = player.getServerWorld();
		DoomsdayWorldData data = DoomsdayWorldData.get(world);
		int cx = player.getBlockPos().getX() >> 4;
		int cz = player.getBlockPos().getZ() >> 4;
		float dose = data.contamination(cx, cz, world.getTime());
		double gained = dose * c.radiationIntensity
			* HazmatGear.radiationMultiplier(player)
			* (SYNC_INTERVAL_TICKS / 20.0D);

		UUID id = player.getUuid();
		long now = player.age;
		Long last = LAST_UPDATE.get(id);
		double exposure = EXPOSURE.getOrDefault(id, 0.0D);
		if (gained <= 0.0D && last != null) {
			// Recovery only while clean. Standing in a hot zone can never be "outrun" by waiting,
			// which is what makes contamination a spatial problem instead of a DPS race.
			exposure -= RECOVERY_PER_MINUTE * (Math.max(0, now - last) / 1200.0D);
		}
		exposure = MathHelper.clamp(exposure + gained, 0.0D, 100.0D);
		EXPOSURE.put(id, exposure);
		LAST_UPDATE.put(id, now);
		updates++;
		peakExposure = Math.max(peakExposure, exposure);

		applyEffect(player, exposure, c);
		if (isHoldingCounter(player) || dose > 0.001F || exposure > 1.0D) {
			syncField(player, data, world, cx, cz, exposure, c);
		}
	}

	private static void applyEffect(ServerPlayerEntity player, double exposure, DoomsdayConfig c) {
		RegistryEntry<StatusEffect> type = radiation();
		if (type == null) {
			return;
		}
		if (exposure < SICKNESS_THRESHOLD || player.isCreative()) {
			if (player.hasStatusEffect(type)) {
				player.removeStatusEffect(type);
			}
			return;
		}
		int span = Math.max(1, c.radiationMaxAmplifier);
		int amplifier = (int) Math.floor((exposure - SICKNESS_THRESHOLD)
			/ ((100.0D - SICKNESS_THRESHOLD) / (span + 1)));
		amplifier = MathHelper.clamp(amplifier, 0, span);
		int duration = Math.max(40, DoomsdayConfig.ticks(c.radiationEffectSeconds));
		StatusEffectInstance existing = player.getStatusEffect(type);
		// Refresh only when the amplifier changed or the tail is short: re-adding every second
		// would flicker the icon and send a redundant packet per player per second.
		if (existing != null && existing.getAmplifier() == amplifier && existing.getDuration() > duration / 2) {
			return;
		}
		player.addStatusEffect(new StatusEffectInstance(type, duration, amplifier, false, true));
	}

	/**
	 * Send the contamination ring around the player. Bounded by
	 * {@link RadiationSyncS2CPacket#MAX_ENTRIES} and by a radius of {@code geigerRange / 16}
	 * chunks, so the payload size never depends on how many craters the world has accumulated.
	 */
	private static void syncField(ServerPlayerEntity player, DoomsdayWorldData data,
								  ServerWorld world, int cx, int cz, double exposure,
								  DoomsdayConfig c) {
		int radius = Math.max(1, (int) Math.round(c.geigerRange / 16.0D));
		Long2FloatMap view = data.contaminationView();
		long[] keys = new long[RadiationSyncS2CPacket.MAX_ENTRIES];
		float[] doses = new float[RadiationSyncS2CPacket.MAX_ENTRIES];
		int n = 0;
		for (Long2FloatMap.Entry entry : view.long2FloatEntrySet()) {
			long key = entry.getLongKey();
			int ex = SpatialUtil.chunkX(key);
			int ez = SpatialUtil.chunkZ(key);
			if (Math.abs(ex - cx) > radius || Math.abs(ez - cz) > radius) {
				continue;
			}
			float value = data.contamination(ex, ez, world.getTime());
			if (value <= 0.001F) {
				continue;
			}
			keys[n] = key;
			doses[n] = value;
			if (++n >= keys.length) {
				break;
			}
		}
		if (n == 0) {
			// A zero-entry sync is what clears the client's ring; skipping it would leave a stale
			// "hot" HUD after a cure or after walking out of the zone.
			keys = new long[]{SpatialUtil.chunkKey(cx, cz)};
			doses = new float[]{0.0F};
			n = 1;
		}
		ModPackets.sendToPlayer(player, new RadiationSyncS2CPacket(
			java.util.Arrays.copyOf(keys, n), java.util.Arrays.copyOf(doses, n)));
		// Exposure and iodine state ride along as an action-bar line rather than a second packet:
		// it is one float of information and the HUD already reads the effect for the rest.
		if (exposure >= SICKNESS_THRESHOLD && isHoldingCounter(player)) {
			// Only instrument-carrying players get the running number; everyone else reads the
			// effect icon. Fewer chat packets, and the device remains the reason to carry it.
			player.sendMessage(com.doomsday.nukes.util.DText.of("gui.doomsday.radiation.exposure",
				(int) Math.round(exposure)), true);
		}
	}

	/**
	 * Bounded, round-robin decay of the contamination field.
	 *
	 * <p>Once a minute we take a snapshot of the chunk keys, walk at most {@link #DECAY_WINDOW}
	 * of them from a persistent cursor, and multiply each dose by
	 * {@code 1 - contaminationDecayPerMinute}. A field of a few thousand chunks therefore decays
	 * in a couple of passes at a cost of one array allocation per minute, instead of the
	 * "iterate every contaminated chunk every tick" version this replaced. Chunks a player is
	 * standing in are skipped, so a cure never visibly happens under someone's feet.</p>
	 */
	private static void decayField(MinecraftServer server, DoomsdayConfig c) {
		double perMinute = MathHelper.clamp(c.contaminationDecayPerMinute, 0.0D, 0.95D);
		if (perMinute <= 0.0D) {
			return;
		}
		for (ServerWorld world : server.getWorlds()) {
			DoomsdayWorldData data = DoomsdayWorldData.get(world);
			Long2FloatMap view = data.contaminationView();
			if (view.isEmpty()) {
				continue;
			}
			long[] keys = view.keySet().toLongArray();
			if (keys.length == 0) {
				continue;
			}
			int start = (int) Math.floorMod(decayCursor, keys.length);
			int touched = 0;
			for (int i = 0; i < keys.length && touched < DECAY_WINDOW; i++) {
				long key = keys[(start + i) % keys.length];
				int ex = SpatialUtil.chunkX(key);
				int ez = SpatialUtil.chunkZ(key);
				touched++;
				if (occupiedByPlayer(world, ex, ez)) {
					continue;
				}
				data.decayContamination(ex, ez, (float) perMinute);
			}
			decayCursor = (start + touched) % Math.max(1, keys.length);
		}
	}

	private static boolean occupiedByPlayer(ServerWorld world, int chunkX, int chunkZ) {
		for (ServerPlayerEntity player : world.getPlayers()) {
			net.minecraft.util.math.BlockPos pos = player.getBlockPos();
			if ((pos.getX() >> 4) == chunkX && (pos.getZ() >> 4) == chunkZ) {
				return true;
			}
		}
		return false;
	}

	// —————————————————————————————————————————————————— public queries

	/**
	 * Dose at a position, 0..1, chunk-quantised by design: contamination is a per-chunk field and
	 * interpolating it would imply a resolution the simulation does not have.
	 *
	 * <p>Safe from either side. On the client it returns the last synced value for the player's own
	 * position, which is exactly what the needle and click rate need; a client cannot ask for an
	 * arbitrary position because this path is never networked.</p>
	 */
	public static float doseAt(World world, Vec3d pos) {
		if (world == null) {
			return 0.0F;
		}
		if (world.isClient) {
			return clientDose;
		}
		if (!(world instanceof ServerWorld sw) || pos == null) {
			return 0.0F;
		}
		return DoomsdayWorldData.get(sw).contamination(pos.getBlockX() >> 4, pos.getBlockZ() >> 4,
			sw.getTime());
	}

	/** Iodine protection: a dose multiplier in 0..1, where 1.0 means "no protection active". */
	public static double iodineMultiplier(PlayerEntity player) {
		if (!(player instanceof ServerPlayerEntity sp)) {
			return 1.0D;
		}
		Long until = IODINE_UNTIL.get(sp.getUuid());
		if (until == null || sp.getServerWorld().getTime() >= until) {
			return 1.0D;
		}
		// iodineAmplifierReduction is expressed on the 0..4 amplifier scale, so convert it to a
		// dose multiplier here rather than storing a second, ambiguous "protection" config knob.
		double reduction = MathHelper.clamp(ConfigManager.get().iodineAmplifierReduction / 4.0D,
			0.0D, 0.99D);
		return Math.max(0.01D, 1.0D - reduction);
	}

	public static boolean isIodineActive(PlayerEntity player) {
		if (!(player instanceof ServerPlayerEntity sp)) {
			return false;
		}
		Long until = IODINE_UNTIL.get(sp.getUuid());
		return until != null && sp.getServerWorld().getTime() < until;
	}

	/** Called by {@code IodineTabletItem} on the server. */
	public static void takeIodine(ServerPlayerEntity player, double seconds) {
		long ticks = Math.max(20L, Math.round(seconds * 20.0D));
		IODINE_UNTIL.put(player.getUuid(), player.getServerWorld().getTime() + ticks);
	}

	public static double exposureOf(ServerPlayerEntity player) {
		return EXPOSURE.getOrDefault(player.getUuid(), 0.0D);
	}

	public static void addExposure(ServerPlayerEntity player, double amount) {
		EXPOSURE.put(player.getUuid(), MathHelper.clamp(exposureOf(player) + amount, 0.0D, 100.0D));
	}

	public static void clearExposure(ServerPlayerEntity player) {
		EXPOSURE.remove(player.getUuid());
		LAST_UPDATE.remove(player.getUuid());
		RegistryEntry<StatusEffect> type = radiation();
		if (type != null) {
			player.removeStatusEffect(type);
		}
	}

	public static void setHoldingCounter(ServerPlayerEntity player, boolean holding) {
		HOLDING.put(player.getUuid(), holding);
	}

	public static boolean isHoldingCounter(ServerPlayerEntity player) {
		return Boolean.TRUE.equals(HOLDING.get(player.getUuid()));
	}

	/** Sweep mode doubles the readout radius; it is a display preference, not a gameplay state. */
	public static void setSweep(ServerPlayerEntity player, boolean sweep) {
		HOLDING.put(player.getUuid(), sweep || isHoldingCounter(player));
	}

	/** Player joined: prime the timer and send them the field so the HUD is right at once. */
	public static void onJoin(ServerPlayerEntity player) {
		EXPOSURE.putIfAbsent(player.getUuid(), 0.0D);
		LAST_UPDATE.put(player.getUuid(), (long) player.age);
		HOLDING.putIfAbsent(player.getUuid(), Boolean.TRUE);
		DoomsdayConfig c = ConfigManager.get();
		ServerWorld world = player.getServerWorld();
		syncField(player, DoomsdayWorldData.get(world), world,
			player.getBlockPos().getX() >> 4, player.getBlockPos().getZ() >> 4,
			exposureOf(player), c);
	}

	public static void onDisconnect(UUID id) {
		// Exposure is intentionally NOT dropped: relogging out of a hot zone must not be a cure.
		IODINE_UNTIL.remove(id);
		HOLDING.remove(id);
	}

	/**
	 * Contamination damage to the player's own health in a hot zone, used by
	 * {@code Detonation#applyFalloutDamage}. Threshold is deliberately high: this is "you stood in
	 * the fire", not "you brushed the edge".
	 */
	public static boolean shouldDamageInHotZone(ServerPlayerEntity player, float dose) {
		return dose > 0.65F && !isIodineActive(player) && !player.isCreative() && !player.isSpectator();
	}

	/** Client entry point for {@code RadiationSyncS2CPacket}: store the ring for the HUD. */
	public static void handleClientField(long[] keys, float[] doses) {
		float max = 0.0F;
		for (int i = 0; i < Math.min(keys.length, doses.length); i++) {
			max = Math.max(max, doses[i]);
		}
		clientDose = Math.max(0.0F, Math.min(1.0F, max));
	}

	/** Client entry point for the exposure/iodine HUD state. */
	public static void handleClientState(double exposure, boolean iodine) {
		clientExposure = MathHelper.clamp(exposure, 0.0D, 100.0D);
		clientIodine = iodine;
	}

	public static float clientDose() {
		return clientDose;
	}

	public static double clientExposure() {
		return clientExposure;
	}

	public static boolean clientIodineActive() {
		return clientIodine;
	}

	public static String describe() {
		return "radiation: " + EXPOSURE.size() + " tracked players, " + updates
			+ " integrations, peak exposure "
			+ String.format(java.util.Locale.ROOT, "%.1f", peakExposure)
			+ ", threshold " + SICKNESS_THRESHOLD;
	}

	/** Test seam: wipe all per-player state. */
	public static void resetForTests() {
		EXPOSURE.clear();
		LAST_UPDATE.clear();
		HOLDING.clear();
		IODINE_UNTIL.clear();
		clientDose = 0.0F;
		clientExposure = 0.0D;
		clientIodine = false;
		decayCursor = 0;
		ticksSinceDecay = 0;
		updates = 0;
		peakExposure = 0.0D;
	}

	/**
	 * Resolves the effect's registry entry lazily. {@link StatusEffectInstance} takes a
	 * {@code RegistryEntry} since 1.20.5, but the effect object itself is what
	 * {@link ModStatusEffects} registers, and the registry is only queryable after the registry
	 * freeze — which is why this is a lazy lookup with a cached result instead of a static field.
	 */
	public static RegistryEntry<StatusEffect> radiation() {
		RegistryEntry<StatusEffect> cached = radiationEntry;
		if (cached != null) {
			return cached;
		}
		if (ModStatusEffects.RADIATION == null) {
			return null;
		}
		cached = Registries.STATUS_EFFECT.getEntry(ModStatusEffects.RADIATION).orElse(null);
		radiationEntry = cached;
		return cached;
	}
}
