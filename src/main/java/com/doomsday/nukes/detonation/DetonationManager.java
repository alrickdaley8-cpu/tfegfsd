package com.doomsday.nukes.detonation;

import com.doomsday.nukes.DoomsdayNukes;
import com.doomsday.nukes.block.entity.NukeBlockEntity;
import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.config.DoomsdayConfig;
import com.doomsday.nukes.network.ModPackets;
import com.doomsday.nukes.network.packet.DetonationS2CPacket;
import com.doomsday.nukes.sound.ModSounds;
import com.doomsday.nukes.world.CraterGenerator;
import com.doomsday.nukes.world.DoomsdayWorldData;
import com.doomsday.nukes.world.TerrainWorkQueue;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The single server-side owner of every live detonation. This is the "minimal permanent
 * ticker" the performance rules ask for: one {@code END_SERVER_TICK} callback for the whole
 * mod, which costs ~0 when nothing is exploding.
 *
 * <h2>Responsibilities</h2>
 * <ol>
 *   <li>Create {@link Detonation} instances with an absolute start time on the world clock,
 *       so clients can reconstruct the whole event from one packet.</li>
 *   <li>Drive each detonation's stage machine and terrain budget.</li>
 *   <li>Bound concurrency: past {@code maxConcurrentDetonations} a new detonation is
 *       <em>merged</em> into the nearest live one (its yield adds, its origin takes the
 *       weighted mean) instead of spawning another independent storm of effects. This is
 *       what stops "nuke the spawn 40 times" from becoming a server-killer.</li>
 *   <li>Resume deferred craters for chunks that have now loaded, sharing one
 *       {@link TerrainWorkQueue} so the global block budget holds no matter how many
 *       detonations are queued.</li>
 * </ol>
 *
 * <p>The class is deliberately instance-based (not static) so a server restart replaces it
 * cleanly and no state can leak between worlds.</p>
 */
public final class DetonationManager {
	private static final class Entry {
		final ServerWorld world;
		final Detonation detonation;

		Entry(ServerWorld world, Detonation detonation) {
			this.world = world;
			this.detonation = detonation;
		}
	}

	private final ArrayList<Entry> active = new ArrayList<>(4);
	/**
	 * Terrain work deferred by chunk unloads, <b>one queue per world</b>.
	 *
	 * <p>A queue stores bare {@code (BlockPos, BlockState)} pairs with no dimension attached, so a
	 * single shared queue would happily write a Tsar Bomba crater into whichever world happened to
	 * be first in {@code server.getWorlds()}. That is not a perf trade-off, it is corruption, and
	 * the per-world map is the whole fix. The map is only touched from the server thread — the same
	 * thread that ticks detonations — so it needs no lock.</p>
	 */
	private final java.util.IdentityHashMap<ServerWorld, TerrainWorkQueue> deferred =
		new java.util.IdentityHashMap<>(2);
	/**
	 * Armed devices, for reconciliation and for {@code /doomsday devices}. <b>Not</b> the countdown:
	 * the timer lives in the block's own scheduled-tick chain, so an armed device costs nothing when
	 * nothing is watching and survives a chunk unload. This list exists only to (a) let a reloaded
	 * or overdue device be found without scanning the world, and (b) answer a command.
	 */
	private final ArrayList<ArmedDevice> armedDevices = new ArrayList<>(4);
	private int nextId = 1;
	private long totalDetonations;
	private long totalMerged;
	private MinecraftServer server;

	// ———————————————————————————————————————————————————— lifecycle

	public void onServerStart(MinecraftServer server) {
		this.server = server;
		synchronized (active) {
			active.clear();
		}
		deferred.clear();
		armedDevices.clear();
		// Any plan left over from a crash is re-armed lazily by the world data; nothing to
		// do here beyond making sure stale in-memory state is gone.
		DoomsdayNukes.LOGGER.info("DetonationManager ready (max concurrent: {}).",
			ConfigManager.get().maxConcurrentDetonations);
	}

	public void onServerStop() {
		synchronized (active) {
			active.clear();
		}
		deferred.clear();
		armedDevices.clear();
		this.server = null;
	}

	// ———————————————————————————————————————————————— armed devices

	/** One armed device, held weakly enough that a broken block cannot pin a chunk. */
	private static final class ArmedDevice {
		final ServerWorld world;
		final BlockPos pos;
		NukeBlockEntity entity;
		long lastSeenTime;

		ArmedDevice(ServerWorld world, BlockPos pos, NukeBlockEntity e) {
			this.world = world;
			this.pos = pos;
			this.entity = e;
			this.lastSeenTime = world.getTime();
		}
	}

	/**
	 * Register (or refresh) an armed device. Called from {@code NukeBlockEntity} on arm, on
	 * chunk-load reconciliation, and never per tick.
	 */
	public void registerArmed(ServerWorld world, BlockPos pos, NukeBlockEntity entity) {
		if (world == null || pos == null || entity == null) {
			return;
		}
		synchronized (armedDevices) {
			for (ArmedDevice d : armedDevices) {
				if (d.world == world && d.pos.equals(pos)) {
					d.entity = entity;
					d.lastSeenTime = world.getTime();
					return;
				}
			}
			armedDevices.add(new ArmedDevice(world, pos.toImmutable(), entity));
		}
	}

	/** Forget a device. Idempotent; safe from {@code onRemovedFromWorld}. */
	public void unregisterArmed(ServerWorld world, BlockPos pos) {
		if (world == null || pos == null) {
			return;
		}
		synchronized (armedDevices) {
			for (java.util.Iterator<ArmedDevice> it = armedDevices.iterator(); it.hasNext(); ) {
				ArmedDevice d = it.next();
				if (d.world == world && d.pos.equals(pos)) {
					it.remove();
				}
			}
		}
	}

	/** Number of armed devices currently known, after pruning. */
	public int armedCount() {
		pruneArmed();
		synchronized (armedDevices) {
			return armedDevices.size();
		}
	}

	/** Debug lines for {@code /doomsday devices}: one per known armed device. */
	public java.util.List<String> describeArmedDevices() {
		pruneArmed();
		ArrayList<String> out = new ArrayList<>();
		synchronized (armedDevices) {
			for (ArmedDevice d : armedDevices) {
				out.add(d.world.getRegistryKey().getValue() + " " + d.pos.toShortString() + " "
					+ (d.entity != null ? d.entity.describe() : "gone"));
			}
		}
		return out;
	}

	/**
	 * Drop entries whose block entity was removed or whose chunk unloaded. Runs at most once a
	 * second (from {@link #tick}) and only scans a list bounded by the number of armed devices a
	 * server can plausibly have, so a player with 5000 devices in a superflat still gets a cheap
	 * check.
	 */
	private void pruneArmed() {
		long now = System.currentTimeMillis();
		if (now - lastPruneMs < 1000L) {
			return;
		}
		lastPruneMs = now;
		synchronized (armedDevices) {
			for (java.util.Iterator<ArmedDevice> it = armedDevices.iterator(); it.hasNext(); ) {
				ArmedDevice d = it.next();
				boolean alive = false;
				if (d.entity instanceof NukeBlockEntity nuke && !nuke.isRemoved()
					&& nuke.getWorld() == d.world && d.world.isChunkLoaded(d.pos)) {
					// A device that finished its countdown stays registered for one more pass so
					// /doomsday devices can still report it; the block's own disarm path removes it.
					alive = nuke.isArmed() || nuke.ticksRemaining() > 0;
				}
				if (!alive) {
					it.remove();
				}
			}
		}
	}

	private long lastPruneMs;

	// ——————————————————————————————————————————————————— trigger

	/**
	 * Starts a detonation. Safe to call from any server-thread context.
	 *
	 * @param world      the world to mutate
	 * @param origin     exact detonation centre (block-space, already offset by the device)
	 * @param originBlock the block position stored for HUD/compass/targeting
	 * @param preset     which device this was
	 * @return the detonation, or an {@link MergedResult}-style existing instance if merged
	 */
	public Detonation detonate(ServerWorld world, Vec3d origin, BlockPos originBlock,
							   NukePreset preset) {
		DoomsdayConfig c = ConfigManager.get();
		ConfigManager.ResolvedTuning tuning = ConfigManager.tuning(preset);

		Detonation merged = tryMerge(world, origin, preset, tuning, c);
		if (merged != null) {
			totalMerged++;
			broadcast(world, merged, c);
			return merged;
		}

		Detonation d = new Detonation(nextId++, preset, tuning, origin, originBlock,
			world.getTime(), tuning.yieldKt());
		synchronized (active) {
			active.add(new Entry(world, d));
		}
		totalDetonations++;

		broadcast(world, d, c);
		// The boom itself is played by clients at a distance-derived delay (see
		// DetonationClientManager) so the flash/rumble ordering stays physical. We only emit
		// the near-field sound here, once, for the whole world's sound propagation.
		if (c.masterVolume > 0.0D) {
			world.playSound(null, originBlock, ModSounds.MUSHROOM_RUMBLE, SoundCategory.RECORDS,
				(volumeFor(tuning) * (float) c.masterVolume), 0.42F * preset.soundPitch);
		}
		return d;
	}

	private static float volumeFor(ConfigManager.ResolvedTuning tuning) {
		// Loud, but never above the vanilla per-sound normalisation for a 16-chunk radius.
		return (float) com.doomsday.nukes.util.MathUtil.clamp(2.0D + tuning.yieldKt() * 0.02D, 2.0D, 7.5D);
	}

	/**
	 * Merge rule: if there are already {@code maxConcurrentDetonations} live detonations in
	 * this world, add this yield to the nearest one instead of creating a new one. Effects
	 * scale as a function of the summed yield, so the player still sees "two nukes hit" get
	 * bigger; the simulation just does not double up.
	 */
	private Detonation tryMerge(ServerWorld world, Vec3d origin, NukePreset preset,
								ConfigManager.ResolvedTuning tuning, DoomsdayConfig c) {
		synchronized (active) {
			if (active.size() < Math.max(1, c.maxConcurrentDetonations)) {
				return null;
			}
			Entry best = null;
			double bestDist = Double.MAX_VALUE;
			for (Entry e : active) {
				if (e.world != world || e.detonation.isFinished()) {
					continue;
				}
				double d = e.detonation.origin().squaredDistanceTo(origin);
				if (d < bestDist) {
					bestDist = d;
					best = e;
				}
			}
			if (best == null) {
				return null;
			}
			best.detonation.mergeYield(tuning.yieldKt(), preset);
			return best.detonation;
		}
	}

	private void broadcast(ServerWorld world, Detonation d, DoomsdayConfig c) {
		broadcast(world, d, c, null);
	}

	/**
	 * Sends the whole event description. One packet per stage transition is enough to drive a
	 * 90-second cinematic because the client advances its own clock from
	 * {@code startWorldTime}; this is why there is no per-tick entity or particle traffic.
	 */
	private void broadcast(ServerWorld world, Detonation d, DoomsdayConfig c,
						   @Nullable ServerPlayerEntity only) {
		DetonationTimeline t = d.timeline();
		DetonationS2CPacket payload = new DetonationS2CPacket(
			d.id(),
			d.preset().ordinal(),
			d.origin().x, d.origin().y, d.origin().z,
			d.originBlock().getX(), d.originBlock().getY(), d.originBlock().getZ(),
			d.startWorldTime(),
			(float) d.yieldKt(),
			(float) t.flashStart, (float) t.flashLength,
			(float) t.fireballStart, (float) t.fireballLength, (float) t.fireballGrow,
			(float) t.shockwaveStart, (float) t.shockwaveLength,
			(float) t.cloudStart, (float) t.cloudLength,
			(float) t.falloutStart, (float) t.falloutLength,
			(float) t.aftermathStart, (float) t.aftermathLength,
			(float) t.totalLength,
			(float) tuningOf(d).fireballRadius(),
			(float) tuningOf(d).shockwaveRadius(),
			(float) tuningOf(d).craterRadius(),
			(float) tuningOf(d).cloudScale(),
			(float) c.flashDistance,
			(float) c.flashWhiteoutSeconds,
			(float) c.flashDesaturateSeconds,
			(float) c.skyDarkness,
			(float) c.cameraShakeSeconds,
			c.flashEnabled, c.cloudEnabled, c.falloutEnabled, c.atmosphericAftermath
		);
		if (only != null) {
			ModPackets.sendToPlayer(only, payload);
		} else {
			ModPackets.sendToTracking(world, d.origin(), (float) soundReach(c), payload);
		}
	}

	private static double soundReach(DoomsdayConfig c) {
		return Math.min(16000.0D, Math.max(128.0D, c.soundDistance * 8.0D));
	}

	private static ConfigManager.ResolvedTuning tuningOf(Detonation d) {
		return ConfigManager.tuning(d.preset());
	}

	// ————————————————————————————————————————————————————————— tick

	public void tick(MinecraftServer server) {
		DoomsdayConfig c = ConfigManager.get();

		// 1 — live detonations
		boolean anyWork = false;
		synchronized (active) {
			if (!active.isEmpty()) {
				anyWork = true;
				for (Iterator<Entry> it = active.iterator(); it.hasNext(); ) {
					Entry e = it.next();
					e.detonation.tick(e.world);
					if (e.detonation.isFinished()) {
						it.remove();
					}
				}
			}
		}

		// 2 — drain each world's deferred terrain queue (craters for chunks that loaded late)
		if (drainDeferred(server, c)) {
			anyWork = true;
		}

		// 3 — opportunistically re-plan deferred craters. Cheap: a throttle inside the data
		// object keeps this to a couple of chunks per second when idle.
		if (c.griefingEnabled && c.craterEnabled) {
			for (ServerWorld world : server.getWorlds()) {
				DoomsdayWorldData data = DoomsdayWorldData.get(world);
				if (data.pendingTerrainCount() == 0) {
					continue;
				}
				int n = data.applyReadyTerrain(world, queueFor(world), world.getTime(),
					Math.max(200, c.terrainBlocksPerTick / 2), Math.max(1, c.terrainMaxChunksPerTick / 2));
				if (n > 0) {
					anyWork = true;
				}
			}
		}

		if (anyWork && c.verboseLogging) {
			DoomsdayNukes.LOGGER.debug("DetonationManager: {} active, {} armed devices, {}",
				active.size(), armedDevices.size(), describeDeferred());
		}
	}

	/** The queue that belongs to {@code world}, created on first use. */
	private TerrainWorkQueue queueFor(ServerWorld world) {
		TerrainWorkQueue q = deferred.get(world);
		if (q == null) {
			q = new TerrainWorkQueue();
			deferred.put(world, q);
		}
		return q;
	}

	/**
	 * Flush every world's deferred queue, and drop queues whose world has gone away.
	 *
	 * @return true if any work was applied this tick
	 */
	private boolean drainDeferred(MinecraftServer server, DoomsdayConfig c) {
		if (deferred.isEmpty()) {
			return false;
		}
		int budget = Math.max(200, c.terrainBlocksPerTick / 2);
		int maxChunks = Math.max(1, c.terrainMaxChunksPerTick / 2);
		boolean worked = false;
		for (java.util.Iterator<java.util.Map.Entry<ServerWorld, TerrainWorkQueue>> it =
				deferred.entrySet().iterator(); it.hasNext(); ) {
			java.util.Map.Entry<ServerWorld, TerrainWorkQueue> e = it.next();
			ServerWorld world = e.getKey();
			TerrainWorkQueue q = e.getValue();
			if (q.isEmpty()) {
				it.remove();
				continue;
			}
			if (server.getWorld(world.getRegistryKey()) != world) {
				// World unloaded or re-created: the queue's positions belong to an object that no
				// longer exists. Dropping it is correct — the plan is still in DoomsdayWorldData and
				// will be re-enqueued when that world comes back.
				q.clear();
				it.remove();
				continue;
			}
			worked |= q.flush(world, budget, maxChunks) > 0;
			if (q.isEmpty()) {
				it.remove();
			}
		}
		return worked;
	}

	/** The queue the generator should spill unloaded-chunk work into for this world. */
	public TerrainWorkQueue deferredQueue(ServerWorld world) {
		return queueFor(world);
	}

	public String describeDeferred() {
		if (deferred.isEmpty()) {
			return "deferred: none";
		}
		StringBuilder sb = new StringBuilder("deferred: ");
		int n = 0;
		for (java.util.Map.Entry<ServerWorld, TerrainWorkQueue> e : deferred.entrySet()) {
			if (n++ > 0) {
				sb.append(", ");
			}
			sb.append(e.getKey().getRegistryKey().getValue().getPath()).append('=').append(e.getValue().describe());
		}
		return sb.toString();
	}

	// ———————————————————————————————————————————————————— queries

	public int liveCount() {
		synchronized (active) {
			int n = 0;
			for (Entry e : active) {
				if (!e.detonation.isFinished()) {
					n++;
				}
			}
			return n;
		}
	}

	public long totalDetonations() {
		return totalDetonations;
	}

	public long totalMerged() {
		return totalMerged;
	}

	public List<Detonation> live() {
		ArrayList<Detonation> out = new ArrayList<>(active.size());
		synchronized (active) {
			for (Entry e : active) {
				if (!e.detonation.isFinished()) {
					out.add(e.detonation);
				}
			}
		}
		return out;
	}

	/**
	 * Sends the live state to one player who just joined or changed dimension, so a
	 * reconnect during a 90-second cloud still shows the cloud instead of an empty sky.
	 */
	public void syncPlayer(ServerPlayerEntity player) {
		DoomsdayConfig c = ConfigManager.get();
		ServerWorld world = player.getServerWorld();
		synchronized (active) {
			for (Entry e : active) {
				if (e.world == world && !e.detonation.isFinished()) {
					broadcast(world, e.detonation, c, player);
				}
			}
		}
	}

	public String describe() {
		StringBuilder sb = new StringBuilder();
		synchronized (active) {
			sb.append("detonations: live=").append(active.size())
				.append(" total=").append(totalDetonations)
				.append(" merged=").append(totalMerged);
			for (Entry e : active) {
				Detonation d = e.detonation;
				sb.append("\n  #").append(d.id()).append(' ').append(d.preset().name())
					.append(" stage=").append(d.stage().key())
					.append(" t=").append(String.format(java.util.Locale.ROOT, "%.1fs",
						d.timeAt(e.world.getTime())))
					.append(" yield=").append(String.format(java.util.Locale.ROOT, "%.1fkt", d.yieldKt()))
					.append(' ').append(d.terrainQueue().describe());
			}
		}
		sb.append("\n  armed devices: ").append(armedCount());
		sb.append("\n  ").append(describeDeferred());
		return sb.toString();
	}

}
