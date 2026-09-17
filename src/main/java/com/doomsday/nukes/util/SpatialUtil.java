package com.doomsday.nukes.util;

/**
 * Voxel/region primitives shared by the crater generator, the terrain queue and the
 * shockwave front. Kept Minecraft-free so the geometry is unit-testable and so the client
 * and server provably agree on which blocks a wavefront covers.
 */
public final class SpatialUtil {
	/**
	 * Scratch buffer used by {@link #sphereShell}. Sized for a radius-400 sphere shell
	 * (surface area ~2·10^5 cells); callers must not nest two uses.
	 *
	 * <p>Shared rather than per-call because {@code sphereShell} is invoked once per
	 * detonation per band, and a 2.4 MB allocation inside a server tick is exactly the
	 * kind of thing that shows up as a hitch. Access is single-threaded (server tick).</p>
	 */
	public static final int[] SHELL_SCRATCH = new int[3 * 400_000];

	private SpatialUtil() {
	}

	// ——————————————————————————————————————————————————— chunk math

	public static long chunkKey(int chunkX, int chunkZ) {
		return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
	}

	public static int chunkX(long key) {
		return (int) (key >> 32);
	}

	public static int chunkZ(long key) {
		return (int) key;
	}

	public static int toChunkIndex(int blockCoord) {
		return blockCoord >> 4;
	}

	/** Chunk count spanned by an axis-aligned block range (inclusive). */
	public static int chunksSpanned(int minBlock, int maxBlock) {
		return (maxBlock >> 4) - (minBlock >> 4) + 1;
	}

	// —————————————————————————————————————————————————— region tests

	/** Squared distance from a point to an axis-aligned box; 0 when the point is inside. */
	public static double sqDistanceToBox(double px, double py, double pz,
										 double minX, double minY, double minZ,
										 double maxX, double maxY, double maxZ) {
		double dx = px < minX ? minX - px : (px > maxX ? px - maxX : 0.0D);
		double dy = py < minY ? minY - py : (py > maxY ? py - maxY : 0.0D);
		double dz = pz < minZ ? minZ - pz : (pz > maxZ ? pz - maxZ : 0.0D);
		return dx * dx + dy * dy + dz * dz;
	}

	/**
	 * Enumerates the cells of a sphere (or, when {@code shell < radius}, a spherical
	 * shell) into {@code out} as packed relative triples.
	 *
	 * <p>Implementation notes, because this runs inside a server tick for a 100 kt
	 * device: the per-Y slice radius is computed with one {@code sqrt} per row instead of
	 * one per cell, the hollow-core rejection uses integer multiply-adds only, and the
	 * {@code limit} is a hard stop so a hostile config value can never turn into a
	 * multi-second freeze. The return value is always the count actually written, so a
	 * truncated scan is visible to the caller instead of silently wrong.</p>
	 *
	 * @param radius outer radius, blocks (&gt;= 1)
	 * @param shell  thickness of the retained shell; {@code >= radius} means solid
	 * @param out    destination, {@code out.length >= 3 * expectedCells}
	 * @param limit  maximum number of cells to write
	 * @return cells written
	 */
	public static int sphereShell(int radius, int shell, int[] out, int limit) {
		int r = Math.max(1, radius);
		int inner = Math.min(r, Math.max(0, r - Math.max(1, shell)));
		long r2 = (long) r * r;
		long inner2 = (long) inner * inner;
		int n = 0;
		int written = 0;
		for (int y = -r; y <= r; y++) {
			long dy2 = (long) y * y;
			long remOuter = r2 - dy2;
			if (remOuter < 0L) {
				continue;
			}
			int rx = (int) Math.sqrt((double) remOuter);
			for (int x = -rx; x <= rx; x++) {
				long rowOuter = remOuter - (long) x * x;
				if (rowOuter < 0L) {
					continue;
				}
				int rz = (int) Math.sqrt((double) rowOuter);
				long innerRest = inner2 - dy2 - (long) x * x;
				int skipTo = innerRest > 0L ? (int) Math.sqrt((double) innerRest) : -1;
				for (int z = -rz; z <= rz; z++) {
					if (skipTo >= 0 && Math.abs(z) <= skipTo && (long) z * z < innerRest) {
						continue;
					}
					if (n + 3 > limit) {
						return written;
					}
					out[n] = x;
					out[n + 1] = y;
					out[n + 2] = z;
					n += 3;
					written++;
				}
			}
		}
		return written;
	}

	/** Cells in a spherical shell, without materialising them (used for budget planning). */
	public static int estimateShellCells(int radius, int shell) {
		int r = Math.max(1, radius);
		int inner = Math.min(r, Math.max(0, r - Math.max(1, shell)));
		double outer = 4.0D / 3.0D * Math.PI * r * r * r;
		double hole = 4.0D / 3.0D * Math.PI * inner * inner * inner;
		return (int) Math.max(1L, Math.round(outer - hole));
	}

	/** Integer ceiling square root, for band walks that must not miss a chunk row. */
	public static int isqrtCeil(long v) {
		if (v <= 0L) {
			return 0;
		}
		long r = (long) Math.sqrt((double) v) + 1L;
		while (r * r > v && r > 1L) {
			r--;
		}
		return (int) r;
	}
}
