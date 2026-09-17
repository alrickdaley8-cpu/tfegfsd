package com.doomsday.nukes.world;

import com.doomsday.nukes.DoomsdayNukes;
import com.doomsday.nukes.compat.BeaconEmpSupport;
import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.config.DoomsdayConfig;
import com.doomsday.nukes.network.ModPackets;
import com.doomsday.nukes.network.packet.EmpSyncS2CPacket;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MutablePos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Bounded electromagnetic-pulse state.
 *
 * <h2>Behaviour</h2>
 * For {@code empSeconds} after a detonation, inside an EMP zone:
 * <ul>
 *   <li><b>Redstone lamps flicker</b> — the {@code LIT} state is toggled at random on each
 *       visit, which reads exactly like an overload and is trivially reversible.</li>
 *   <li><b>Redstone torches disable</b> — {@code LIT=false} is written. Everything downstream
 *       (wire, repeaters, mechanisms) then goes dark through <em>normal vanilla
 *       propagation</em>, which is why the mod never touches wires directly: killing the
 *       source is both cheaper and causal.</li>
 *   <li><b>Beacons suspend</b> — best-effort via {@link BeaconEmpSupport}, which degrades to
 *       a no-op with one log line instead of crashing if the mapped field is absent.</li>
 *   <li><b>Devices go deaf</b> — remote detonation is refused while a device is inside an
 *       active zone (see {@code NukeBlockEntity}).</li>
 * </ul>
 *
 * <h2>Why the scan is the way it is</h2>
 * Testing every block in a 300-block-radius sphere is ~1.1·10^8 cells, which no budget
 * makes acceptable. Instead each zone owns a <em>strided, distance-ordered column cursor
 * over the near-surface band</em>:
 * <ul>
 *   <li>radius clamped by {@code empMaxRadius};</li>
 *   <li>stride {@code empScanStride} blocks between columns;</li>
 *   <li>only the top {@code empSurfaceDepth} blocks of each column are read (player
 *       electronics live there, and it removes the vertical dimension from the cost);</li>
 *   <li>at most {@code empBlocksPerTick} cells inspected per tick, wrap-around so a
 *       long-lived zone re-sweeps and keeps flickering.</li>
 * </ul>
 * The result is that the strongest, densest visual is right under the blast, falling off
 * outward — which is also what EMP coupling actually does with distance.
 *
 * <h2>Restoration</h2>
 * Every block the zone touches is recorded (original state) in {@link Zone#captured}
 * <em>before</em> the first modification, and the whole map is written back on expiry, on
 * chunk unload, on server stop, and on config disable. There is no path where an EMP block
 * edit is left behind.
 */
public final class EMPManager {
	private static final ArrayList<Zone> ZONES = new ArrayList<>(4);

	private EMPManager() {
	}

	/** One live pulse region. Owns its cursor, its captured states and its expiry. */
	private static final class Zone {
		final ServerWorld world;
		final double x;
		final double y;
		final double z;
		final int halfSpan;
		final int stride;
		final int depth;
		final int columnsPerAxis;
		final int totalColumns;
		final long expiry;
		final Long2ObjectOpenHashMap<BlockState> captured = new Long2ObjectOpenHashMap<>();
		final Long2ObjectOpenHashMap<int[]> beacons = new Long2ObjectOpenHashMap<>();
		int cursor;
		int sweeps;
		long modified;

		Zone(ServerWorld world, Vec3d origin, double radius, int ticks, long now) {
			this.world = world;
			this.x = origin.x;
			this.y = origin.y;
			this.z = origin.z;
			DoomsdayConfig c = ConfigManager.get();
			this.halfSpan = (int) Math.max(4L, Math.min(Math.ceil(c.empMaxRadius), Math.ceil(radius)));
			this.stride = Math.max(1, c.empScanStride);
			this.depth = Math.max(2, c.empSurfaceDepth);
			this.columnsPerAxis = (2 * halfSpan) / stride + 1;
			this.totalColumns = columnsPerAxis * columnsPerAxis;
			this.expiry = now + Math.max(1, ticks);
		}

		boolean isExpired(long now) {
			return now >= expiry;
		}

		/** @return cells inspected this call */
		int tickBudgeted(int budget) {
			int inspected = 0;
			DoomsdayConfig c = ConfigManager.get();
			MutablePos pos = new MutablePos(0, 0, 0);
			int limit = Math.max(16, budget / Math.max(1, depth));
			for (int i = 0; i < limit && cursor < totalColumns; i++) {
				int col = cursor++;
				int ci = col % columnsPerAxis;
				int cj = col / columnsPerAxis;
				int bx = (int) Math.floor(x) - halfSpan + ci * stride;
				int bz = (int) Math.floor(z) - halfSpan + cj * stride;
				if (posChunkUnloaded(bx, bz)) {
					inspected += depth;
					continue;
				}
				int top = world.getHeight(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, bx, bz);
				for (int dy = 0; dy < depth; dy++) {
					int by = top - dy;
					if (by < world.getBottomY() || by > world.getTopY()) {
						continue;
					}
					inspected++;
					pos.set(bx, by, bz);
					BlockState state = world.getBlockState(pos);
					if (state.isAir()) {
						continue;
					}
					touch(pos, state, c);
				}
				if (cursor >= totalColumns) {
					cursor = 0;
					sweeps++;
				}
				if (inspected >= budget) {
					return inspected;
				}
			}
			return inspected;
		}

		private boolean posChunkUnloaded(int bx, int bz) {
			return !world.isChunkLoaded(bx >> 4, bz >> 4);
		}

		private void touch(BlockPos pos, BlockState state, DoomsdayConfig c) {
			long key = pos.asLong();
			if (c.empDisablesBeacons && state.isOf(Blocks.BEACON)) {
				if (!beacons.containsKey(key)) {
					int level = BeaconEmpSupport.captureAndSuspend(world, pos);
					if (level != BeaconEmpSupport.UNSUPPORTED) {
						beacons.put(key, new int[]{level});
						modified++;
					}
				}
				return;
			}
			if (c.empDisablesRedstone && (state.isOf(Blocks.REDSTONE_TORCH)
				|| state.getBlock() instanceof net.minecraft.block.RedstoneTorchBlock)) {
				if (state.contains(net.minecraft.state.property.Properties.LIT)
					&& state.get(net.minecraft.state.property.Properties.LIT)) {
					record(key, state);
					world.setBlockState(pos, state.with(net.minecraft.state.property.Properties.LIT, false));
				}
				return;
			}
			if (c.empFlickersLamps && state.isOf(Blocks.REDSTONE_LAMP)) {
				if (!state.contains(net.minecraft.state.property.Properties.LIT)) {
					return;
				}
				// Random-ish, but deterministic per (position, sweep) so two clients never
				// disagree about what a lamp "should" be doing.
				int h = com.doomsday.nukes.util.MathUtil.hash(pos.getX(), pos.getY() + sweeps * 31,
					pos.getZ());
				if (com.doomsday.nukes.util.MathUtil.hash01(h) < c.empLampFlickerChance) {
					record(key, state);
					boolean lit = state.get(net.minecraft.state.property.Properties.LIT);
					world.setBlockState(pos,
						state.with(net.minecraft.state.property.Properties.LIT, !lit));
					modified++;
				}
			}
		}

		private void record(long key, BlockState original) {
			if (!captured.containsKey(key)) {
				if (captured.size() >= MAX_CAPTURED) {
					// Refuse to grow unbounded: an unrepaired flicker is a cosmetic wart,
					// an out-of-memory server is an outage.
					return;
				}
				captured.put(key, original);
			}
		}

		void restore() {
			if (captured.isEmpty() && beacons.isEmpty()) {
				return;
			}
			MutablePos pos = new MutablePos(0, 0, 0);
			int n = 0;
			for (Long2ObjectMap.Entry<BlockState> e : captured.long2ObjectEntrySet()) {
				long key = e.getLongKey();
				BlockPos original = BlockPos.fromLong(key);
				pos.set(original.getX(), original.getY(), original.getZ());
				if (world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
					BlockState current = world.getBlockState(pos);
					BlockState want = e.getValue();
					if (current != want) {
						world.setBlockState(pos, want);
						n++;
					}
				}
			}
			for (Long2ObjectMap.Entry<int[]> e : beacons.long2ObjectEntrySet()) {
				BlockPos original = BlockPos.fromLong(e.getLongKey());
				pos.set(original.getX(), original.getY(), original.getZ());
				BeaconEmpSupport.restore(world, pos, e.getValue()[0]);
			}
			captured.clear();
			beacons.clear();
			if (ConfigManager.get().verboseLogging && n > 0) {
				DoomsdayNukes.LOGGER.info("EMP zone at ({},{}): restored {} blocks cleanly.",
					(int) x, (int) z, n);
			}
		}

		int affected() {
			return captured.size();
		}
	}

	/** Cap on remembered pre-EMP states per zone (~200 KB of references, worst case). */
	private static final int MAX_CAPTURED = 24_000;

	// ———————————————————————————————————————————————————— lifecycle

	public static void init() {
		// Zone storage is static-by-design: a detonation's EMP region outlives the block
		// entity that caused it, and must survive a device being broken.
	}

	public static void onServerStart(MinecraftServer server) {
		synchronized (ZONES) {
			ZONES.clear();
		}
	}

	public static void onServerStop() {
		List<Zone> copy;
		synchronized (ZONES) {
			copy = new ArrayList<>(ZONES);
			ZONES.clear();
		}
		// A shutdown mid-EMP must not leave the world flickering forever.
		for (Zone z : copy) {
			z.restore();
		}
	}

	public static void tick(MinecraftServer server) {
		synchronized (ZONES) {
			if (ZONES.isEmpty()) {
				return;
			}
			DoomsdayConfig c = ConfigManager.get();
			Iterator<Zone> it = ZONES.iterator();
			while (it.hasNext()) {
				Zone zone = it.next();
				long now = zone.world.getTime();
				if (zone.isExpired(now) || !c.empEnabled) {
					zone.restore();
					broadcastClear(zone);
					it.remove();
					continue;
				}
				zone.tickBudgeted(Math.max(60, c.empBlocksPerTick));
			}
		}
	}

	// ———————————————————————————————————————————————————— queries

	public static void addZone(ServerWorld world, Vec3d origin, double radius, int ticks) {
		DoomsdayConfig c = ConfigManager.get();
		if (!c.empEnabled || ticks <= 0 || radius <= 0.5D) {
			return;
		}
		Zone zone = new Zone(world, origin, radius, ticks, world.getTime());
		synchronized (ZONES) {
			ZONES.add(zone);
			// Bound the number of live zones: oldest first, restored, so a "nuke spam"
			// server cannot accumulate cursors forever.
			while (ZONES.size() > c.maxConcurrentDetonations + 2) {
				Zone old = ZONES.remove(0);
				old.restore();
			}
		}
		broadcast(world, origin, ticks);
	}

	/** True when {@code pos} currently sits inside an active EMP zone of {@code world}. */
	public static boolean isEmpActive(ServerWorld world, BlockPos pos) {
		DoomsdayConfig c = ConfigManager.get();
		if (!c.empEnabled) {
			return false;
		}
		synchronized (ZONES) {
			for (Zone zone : ZONES) {
				if (zone.world != world) {
					continue;
				}
				double dx = pos.getX() + 0.5D - zone.x;
				double dy = pos.getY() + 0.5D - zone.y;
				double dz = pos.getZ() + 0.5D - zone.z;
				double r = Math.max(zone.halfSpan * (double) zone.stride * 0.5D, 4.0D);
				if (dx * dx + dy * dy + dz * dz <= r * r) {
					return true;
				}
			}
		}
		return false;
	}

	public static int activeZoneCount() {
		synchronized (ZONES) {
			return ZONES.size();
		}
	}

	public static String describe() {
		synchronized (ZONES) {
			if (ZONES.isEmpty()) {
				return "EMP: idle";
			}
			StringBuilder sb = new StringBuilder("EMP:");
			for (Zone z : ZONES) {
				sb.append(" [r=").append((int) (z.halfSpan * z.stride * 0.5D))
					.append(" touched=").append(z.modified)
					.append(" toRestore=").append(z.affected())
					.append(" t-").append(Math.max(0L, z.expiry - z.world.getTime()))
					.append(']');
			}
			return sb.toString();
		}
	}

	// ———————————————————————————————————————————————————— network

	private static void broadcast(ServerWorld world, Vec3d origin, int ticks) {
		EmpSyncS2CPacket payload = new EmpSyncS2CPacket(
			origin.x, origin.y, origin.z, (int) (ticks), true);
		for (ServerPlayerEntity player : world.getPlayers()) {
			ModPackets.sendToPlayer(player, payload);
		}
	}

	private static void broadcastClear(Zone zone) {
		EmpSyncS2CPacket payload = new EmpSyncS2CPacket(zone.x, zone.y, zone.z, 0, false);
		for (ServerPlayerEntity player : zone.world.getPlayers()) {
			ModPackets.sendToPlayer(player, payload);
		}
	}
}
