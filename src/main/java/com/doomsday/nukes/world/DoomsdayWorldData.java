package com.doomsday.nukes.world;

import com.doomsday.nukes.DoomsdayNukes;
import com.doomsday.nukes.util.SpatialUtil;
import it.unimi.dsi.fastutil.longs.Long2FloatMap;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;

/**
 * Persistent mod state for one world: terrain plans that were deferred because their
 * chunks were not loaded, and the contamination field.
 *
 * <h2>What is stored — and what deliberately is not</h2>
 * A crater is *not* stored as a list of block positions. It is stored as a nine-long
 * procedural plan ({@link DetonationTerrainPlan}) per affected chunk, and re-evaluated on
 * apply. That is the difference between "a Tsar Bomba adds 5 MB of chunk NBT" and "adds a
 * few hundred bytes", and it is only possible because the generator is a pure function of
 * {@code (plan, chunk)} — the reason {@code DetonationTerrainPlan} has no Minecraft types.
 *
 * <p>Contamination is stored per chunk as a dose level (0..1) plus an expiry world-time,
 * which is also what the Geiger counter and radiation application read.</p>
 *
 * <h2>Lifecycle safety</h2>
 * <ul>
 *   <li>Accessed only from the server thread; no locking.</li>
 *   <li>All NBT reads are defensive: a truncated or hand-edited file yields an empty map,
 *       never an exception during world load.</li>
 *   <li>Pending terrain entries expire after {@link #PENDING_TTL_TICKS} so a crater nobody
 *       ever walked back to cannot accumulate forever.</li>
 * </ul>
 */
public final class DoomsdayWorldData extends PersistentState {
	public static final String DATA_ID = "doomsday_world";
	/** ~20 real-time minutes of unloaded-patience before a deferred crater is abandoned. */
	private static final long PENDING_TTL_TICKS = 20L * 60L * 20L;
	private static final int MAX_PLAN_LONGS = 9;

	/** chunkKey → packed plan longs (see {@link DetonationTerrainPlan#write}). */
	private final Long2ObjectOpenHashMap<long[]> pendingTerrain = new Long2ObjectOpenHashMap<>();
	/** chunkKey → world time at which that deferred plan was created. */
	private final Long2LongOpenHashMap pendingSince = new Long2LongOpenHashMap();
	/** chunkKey → dose level 0..1. */
	private final Long2FloatOpenHashMap contamination = new Long2FloatOpenHashMap();
	/** chunkKey → expiry world time. */
	private final Long2LongOpenHashMap contaminationExpiry = new Long2LongOpenHashMap();

	/** Non-serialised: last tick at which we scanned for newly loaded chunks. */
	private long lastScanTick = Long.MIN_VALUE;

	public DoomsdayWorldData() {
		contamination.defaultReturnValue(0.0F);
		contaminationExpiry.defaultReturnValue(Long.MIN_VALUE);
	}

	// ———————————————————————————————————————————————————— access

	public static DoomsdayWorldData get(ServerWorld world) {
		return world.getPersistentStateManager()
			.getOrCreate(DoomsdayWorldData::load, DATA_ID);
	}

	public static DoomsdayWorldData load(NbtCompound nbt) {
		DoomsdayWorldData data = new DoomsdayWorldData();
		if (nbt == null) {
			return data;
		}
		try {
			if (nbt.contains("PendingTerrain", NbtElement.LIST_TYPE)) {
				NbtList list = nbt.getList("PendingTerrain", NbtElement.COMPOUND_TYPE);
				for (int i = 0; i < list.size(); i++) {
					NbtCompound e = list.getCompound(i);
					long chunkKey = e.getLong("Chunk");
					long[] plan = e.getLongArray("Plan");
					if (plan != null && plan.length == MAX_PLAN_LONGS) {
						data.pendingTerrain.put(chunkKey, plan);
						data.pendingSince.put(chunkKey, e.getLong("Since"));
					}
				}
			}
			if (nbt.contains("Contamination", NbtElement.LIST_TYPE)) {
				NbtList list = nbt.getList("Contamination", NbtElement.COMPOUND_TYPE);
				for (int i = 0; i < list.size(); i++) {
					NbtCompound e = list.getCompound(i);
					long chunkKey = e.getLong("Chunk");
					float dose = e.getFloat("Dose");
					long expiry = e.getLong("Expiry");
					if (dose > 0.0F && expiry > 0L) {
						data.contamination.put(chunkKey, Math.min(1.0F, dose));
						data.contaminationExpiry.put(chunkKey, expiry);
					}
				}
			}
		} catch (RuntimeException e) {
			DoomsdayNukes.LOGGER.warn("Ignoring malformed {} section in world data: {}",
				DATA_ID, e.getMessage());
		}
		return data;
	}

	@Override
	public void writeNbt(NbtCompound nbt) {
		NbtList terrain = new NbtList();
		for (Long2ObjectMap.Entry<long[]> entry : pendingTerrain.long2ObjectEntrySet()) {
			NbtCompound e = new NbtCompound();
			e.putLong("Chunk", entry.getLongKey());
			e.putLongArray("Plan", entry.getValue());
			e.putLong("Since", pendingSince.getOrDefault(entry.getLongKey(), 0L));
			terrain.add(e);
		}
		nbt.put("PendingTerrain", terrain);

		NbtList cont = new NbtList();
		for (Long2FloatMap.Entry entry : contamination.long2FloatEntrySet()) {
			long key = entry.getLongKey();
			long expiry = contaminationExpiry.getOrDefault(key, Long.MIN_VALUE);
			if (entry.getFloatValue() > 0.0F) {
				NbtCompound e = new NbtCompound();
				e.putLong("Chunk", key);
				e.putFloat("Dose", entry.getFloatValue());
				e.putLong("Expiry", expiry);
				cont.add(e);
			}
		}
		nbt.put("Contamination", cont);
	}

	// —————————————————————————————————————————————— deferred terrain

	public void addPendingChunk(int chunkX, int chunkZ, DetonationTerrainPlan plan) {
		long key = SpatialUtil.chunkKey(chunkX, chunkZ);
		if (pendingTerrain.containsKey(key)) {
			return;
		}
		java.util.ArrayList<Long> tmp = new java.util.ArrayList<>(MAX_PLAN_LONGS);
		plan.write(tmp);
		long[] packed = new long[tmp.size()];
		for (int i = 0; i < packed.length; i++) {
			packed[i] = tmp.get(i);
		}
		pendingTerrain.put(key, packed);
		markDirty();
	}

	public int pendingTerrainCount() {
		return pendingTerrain.size();
	}

	/**
	 * Re-applies deferred plans whose chunks have since loaded. Called from the
	 * {@code DetonationManager} tick with the same budget rules as the live path, so a
	 * player walking back into a half-made crater sees it complete over a couple of ticks
	 * rather than in one freeze.
	 *
	 * @return plans applied this call
	 */
	public int applyReadyTerrain(ServerWorld world, TerrainWorkQueue queue, long worldTime,
								int budget, int maxEntries) {
		if (pendingTerrain.isEmpty()) {
			return 0;
		}
		// Cheap global throttle: no reason to scan every tick.
		if (lastScanTick != Long.MIN_VALUE && worldTime - lastScanTick < 10L) {
			return 0;
		}
		lastScanTick = worldTime;

		int applied = 0;
		java.util.Iterator<Long2ObjectMap.Entry<long[]>> it =
			pendingTerrain.long2ObjectEntrySet().iterator();
		while (it.hasNext() && applied < maxEntries && !queue.isFull()) {
			Long2ObjectMap.Entry<long[]> entry = it.next();
			long key = entry.getLongKey();
			long since = pendingSince.getOrDefault(key, 0L);
			if (worldTime - since > PENDING_TTL_TICKS) {
				it.remove();
				pendingSince.remove(key);
				continue;
			}
			int cx = SpatialUtil.chunkX(key);
			int cz = SpatialUtil.chunkZ(key);
			if (!world.isChunkLoaded(cx, cz)) {
				continue;
			}
			DetonationTerrainPlan plan = DetonationTerrainPlan.read(entry.getValue());
			if (plan == null) {
				it.remove();
				pendingSince.remove(key);
				continue;
			}
			CraterGenerator.applyChunk(world, plan, cx, cz, queue, budget);
			it.remove();
			pendingSince.remove(key);
			applied++;
		}
		if (applied > 0) {
			markDirty();
		}
		return applied;
	}

	// ——————————————————————————————————————————————— contamination

	public float contamination(int chunkX, int chunkZ, long worldTime) {
		long key = SpatialUtil.chunkKey(chunkX, chunkZ);
		long expiry = contaminationExpiry.getOrDefault(key, Long.MIN_VALUE);
		if (expiry != Long.MIN_VALUE && worldTime > expiry) {
			return 0.0F;
		}
		return Math.max(0.0F, contamination.getOrDefault(key, 0.0F));
	}

	public void addContamination(int chunkX, int chunkZ, float dose, long expiryWorldTime) {
		long key = SpatialUtil.chunkKey(chunkX, chunkZ);
		float next = Math.min(1.0F, contamination.getOrDefault(key, 0.0F) + dose);
		if (next <= 0.0F) {
			return;
		}
		contamination.put(key, next);
		contaminationExpiry.put(key, Math.max(expiryWorldTime,
			contaminationExpiry.getOrDefault(key, Long.MIN_VALUE)));
		markDirty();
	}

	/** @return dose actually removed (for logging / HUD feedback after a cure) */
	public float decayContamination(int chunkX, int chunkZ, float factor) {
		long key = SpatialUtil.chunkKey(chunkX, chunkZ);
		float cur = contamination.getOrDefault(key, 0.0F);
		if (cur <= 0.0F) {
			return 0.0F;
		}
		float next = Math.max(0.0F, cur * (1.0F - Math.min(1.0F, Math.max(0.0F, factor))));
		if (next <= 1.0E-3F) {
			contamination.remove(key);
			contaminationExpiry.remove(key);
		} else {
			contamination.put(key, next);
		}
		markDirty();
		return cur - next;
	}

	public void purgeContaminationNear(int chunkX, int chunkZ, int radiusChunks) {
		boolean changed = false;
		for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
			for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
				long key = SpatialUtil.chunkKey(chunkX + dx, chunkZ + dz);
				if (contamination.containsKey(key)) {
					contamination.remove(key);
					contaminationExpiry.remove(key);
					changed = true;
				}
			}
		}
		if (changed) {
			markDirty();
		}
	}

	public int contaminatedChunkCount() {
		return contamination.size();
	}

	/** Iterate the (chunkKey → dose) view for sync; read-only. */
	public Long2FloatOpenHashMap contaminationView() {
		return contamination;
	}

	public Long2LongOpenHashMap contaminationExpiryView() {
		return contaminationExpiry;
	}

	public String describe() {
		return "worldData[pendingTerrain=" + pendingTerrain.size()
			+ " contaminatedChunks=" + contamination.size() + "]";
	}
}
