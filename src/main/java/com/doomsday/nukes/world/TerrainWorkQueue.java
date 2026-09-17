package com.doomsday.nukes.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;

/**
 * The <b>only</b> path by which Doomsday Nukes mutates terrain. A detonation enqueues
 * position→state pairs; {@code DetonationManager} drains them with a per-tick budget,
 * one contiguous burst per chunk, and never against an unloaded chunk.
 *
 * <h2>Why a queue instead of nested {@code setBlockState} loops</h2>
 * A Tsar Bomba crater is a ~40-block-radius sphere (≈2.6·10^5 cells) and the scorch and
 * fireball bands roughly triple that. Written inline that is a multi-second single tick,
 * because every {@code setBlockState} triggers neighbour updates, relighting and a chunk
 * rebuild request. Batching gives four wins:
 * <ol>
 *   <li><b>Chunk grouping</b> — edits are bucketed by chunk key, so each chunk is rewritten
 *       once, back to back: one rebuild window, cache-friendly access.</li>
 *   <li><b>Idempotence</b> — an edit whose current state already matches is dropped before
 *       it touches the world. On real terrain this removes a large fraction of the scorch
 *       and water work outright.</li>
 *   <li><b>Load-aware</b> — a chunk that got unloaded between plan and drain is dropped and
 *       counted, never force-loaded. Late chunks are re-planned by
 *       {@link DoomsdayWorldData}'s pending-plan list.</li>
 *   <li><b>Bounded</b> — caps on cells/tick, chunks/tick and total pending edits, plus an
 *       observable {@link #drops()} counter so truncation is diagnosable instead of a
 *       mysterious "half a crater".</li>
 * </ol>
 *
 * <p>Server-thread only; no synchronisation, no allocation in the drain loop.</p>
 */
public final class TerrainWorkQueue {
	/** Ceiling on queued edits per detonation; beyond this, new edits are counted as drops. */
	public static final int MAX_PENDING_EDITS = 240_000;

	private static final class Bucket {
		final long key;
		final int chunkX;
		final int chunkZ;
		int size;
		int[] x = new int[128];
		int[] y = new int[128];
		int[] z = new int[128];
		BlockState[] state = new BlockState[128];

		Bucket(long key, int chunkX, int chunkZ) {
			this.key = key;
			this.chunkX = chunkX;
			this.chunkZ = chunkZ;
		}

		void add(int bx, int by, int bz, BlockState next) {
			if (size == x.length) {
				int cap = size + (size >> 1) + 32;
				x = java.util.Arrays.copyOf(x, cap);
				y = java.util.Arrays.copyOf(y, cap);
				z = java.util.Arrays.copyOf(z, cap);
				state = java.util.Arrays.copyOf(state, cap);
			}
			x[size] = bx;
			y[size] = by;
			z[size] = bz;
			state[size] = next;
			size++;
		}

		void compactFrom(int from) {
			int left = size - from;
			if (left > 0) {
				System.arraycopy(x, from, x, 0, left);
				System.arraycopy(y, from, y, 0, left);
				System.arraycopy(z, from, z, 0, left);
				System.arraycopy(state, from, state, 0, left);
			}
			for (int i = 0; i < left; i++) {
				state[i + left] = null;
			}
			size = Math.max(0, left);
		}
	}

	private final Long2ObjectOpenHashMap<Bucket> index = new Long2ObjectOpenHashMap<>(64);
	private final ArrayList<Bucket> buckets = new ArrayList<>(24);
	private int pending;
	private int drops;
	private int applied;

	// ————————————————————————————————————————————————————— enqueue

	/** @return {@code false} when the queue is saturated and this edit was dropped */
	public boolean add(BlockPos pos, BlockState next) {
		return add(pos.getX(), pos.getY(), pos.getZ(), next);
	}

	public boolean add(int bx, int by, int bz, BlockState next) {
		if (next == null) {
			return false;
		}
		if (pending >= MAX_PENDING_EDITS) {
			drops++;
			return false;
		}
		int cx = bx >> 4;
		int cz = bz >> 4;
		long key = com.doomsday.nukes.util.SpatialUtil.chunkKey(cx, cz);
		Bucket bucket = index.get(key);
		if (bucket == null) {
			bucket = new Bucket(key, cx, cz);
			index.put(key, bucket);
			buckets.add(bucket);
		}
		bucket.add(bx, by, bz, next);
		pending++;
		return true;
	}

	// ——————————————————————————————————————————————————————— state

	public boolean isFull() {
		return pending >= MAX_PENDING_EDITS;
	}

	public boolean isEmpty() {
		return pending <= 0;
	}

	public int remaining() {
		return Math.max(0, pending);
	}

	public int applied() {
		return applied;
	}

	public int drops() {
		return drops;
	}

	public int chunks() {
		return buckets.size();
	}

	public void clear() {
		index.clear();
		buckets.clear();
		pending = 0;
	}

	// ————————————————————————————————————————————————————————— drain

	/**
	 * Applies up to {@code budget} writes over at most {@code maxChunks} chunks.
	 *
	 * @return writes applied
	 */
	public int flush(ServerWorld world, int budget, int maxChunks) {
		if (pending <= 0) {
			return 0;
		}
		int used = 0;
		int chunksThisTick = 0;
		BlockPos.Mutable cursor = new BlockPos.Mutable(0, 0, 0);

		// Iterate backwards so buckets can be removed without disturbing indices.
		for (int i = buckets.size() - 1; i >= 0 && used < budget && chunksThisTick < maxChunks; i--) {
			Bucket bucket = buckets.get(i);
			if (bucket.size <= 0) {
				buckets.remove(i);
				continue;
			}
			if (!world.isChunkLoaded(bucket.chunkX, bucket.chunkZ)) {
				// Never force-load. Count it; DetonationManager re-plans on chunk load.
				drops += bucket.size;
				pending -= bucket.size;
				bucket.size = 0;
				buckets.remove(i);
				index.remove(bucket.key);
				continue;
			}
			chunksThisTick++;

			int written = 0;
			for (int j = 0; j < bucket.size && used < budget; j++) {
				cursor.set(bucket.x[j], bucket.y[j], bucket.z[j]);
				BlockState expected = bucket.state[j];
				written = j + 1;
				used++;
				pending--;
				applied++;
				BlockState before = world.getBlockState(cursor);
				if (before == expected) {
					bucket.state[j] = null;
					continue;
				}
				world.setBlockState(cursor, expected);
				bucket.state[j] = null;
			}
			if (written > 0) {
				bucket.compactFrom(written);
			}
			if (bucket.size == 0) {
				buckets.remove(i);
				index.remove(bucket.key);
			}
			// No extra neighbour pass is needed: setBlockState(pos, state) already carries
			// the vanilla UPDATE_ALL semantics (neighbour notification + re-render), and
			// skipping identical states up front keeps the per-block cost near zero.
		}
		return used;
	}

	/** Diagnostics for {@code /doomsday status}. */
	public String describe() {
		return "queue[pending=" + pending + " applied=" + applied + " drops=" + drops
			+ " chunks=" + buckets.size() + "]";
	}

	/**
	 * Small helper the generators use everywhere: only write when something changes.
	 * Keeps {@code World} out of the hot loops and makes the "no-op edit" rule explicit.
	 */
	public static boolean writeIfChanged(World world, BlockPos pos, BlockState state) {
		return world.getBlockState(pos) != state && world.setBlockState(pos, state);
	}
}
