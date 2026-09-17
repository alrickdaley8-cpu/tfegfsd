package com.doomsday.nukes.world;

import com.doomsday.nukes.util.MathUtil;
import com.doomsday.nukes.util.SpatialUtil;

/**
 * The complete geometric description of what a detonation does to terrain, expressed as
 * numbers only — <b>no Minecraft types</b>. That is deliberate:
 *
 * <ul>
 *   <li>It can be unit tested without booting the game ({@code TerrainPlanTest}).</li>
 *   <li>It can be serialised into a single compact NBT record and replayed later, so a
 *       crater whose chunks were unloaded at detonation time still forms when the player
 *       returns — without ever persisting hundreds of thousands of block positions.</li>
 *   <li>It makes the per-chunk work a pure function of {@code (plan, chunkX, chunkZ)},
 *       so chunk order can never change the result (deterministic gameplay).</li>
 * </ul>
 *
 * <h2>Crater profile</h2>
 * A simple "delete everything inside a sphere" crater looks like a ball pit and also
 * destroys far more blocks than necessary. The profile here is the standard explosion
 * crater shape: a <em>spherical cap</em> bowl, a <em>raised rim</em> that peaks at the
 * lip and decays outward, and a <em>spall</em> floor below the rim.
 *
 * <pre>
 *   removed(y &lt; surface) |  fill (rim)
 *        \  bowl  /        ^   ^
 *         \      /________/ \_/ \____
 *                          rimStart   rimEnd
 * </pre>
 */
public final class DetonationTerrainPlan {
	/** Operation codes written into the terrain queue; kept as bytes for compactness. */
	public static final byte OP_NONE = 0;
	public static final byte OP_EXCAVATE = 1;
	public static final byte OP_VITRIFY_FLOOR = 2;
	public static final byte OP_HEAT_STONE = 3;
	public static final byte OP_SCORCH = 4;
	public static final byte OP_CHARRED_LOG = 5;
	public static final byte OP_EVAPORATE = 6;
	public static final byte OP_IGNITE = 7;
	public static final byte OP_STRIP_LEAVES = 8;
	public static final byte OP_BREAK_GLASS = 9;

	/** World floor assumed when a column has no ground; keeps rim math finite. */
	private static final int MIN_Y = -64;
	private static final int MAX_Y = 320;

	public final int originX;
	public final int originY;
	public final int originZ;
	/** Crater bowl radius (blocks). 0 disables excavation. */
	public final double craterRadius;
	/** Bowl depth as a fraction of radius. */
	public final double craterDepthRatio;
	/** Raised rim width as a fraction of radius. */
	public final double rimWidthRatio;
	/** Rim height as a fraction of radius. */
	public final double rimHeightRatio;
	/** Fireball surface effect radius (sand→glass, water→air, flammables). */
	public final double fireballRadius;
	/** Destructive-wave radius for leaf stripping / glass breaking. */
	public final double blastRadius;
	/** Width of the scorch band beyond the rim. */
	public final double scorchBand;
	/** Deterministic seed so retries produce identical terrain. */
	public final long seed;

	public final boolean vitrifyFloor;
	public final boolean evaporateWater;
	public final boolean igniteFlammables;
	public final boolean griefing;

	/** Vertical extent the plan may touch, precomputed so a chunk can be rejected cheaply. */
	public final int minY;
	public final int maxY;
	/** Horizontal extent (blocks from origin) beyond which nothing happens. */
	public final double maxReach;

	public DetonationTerrainPlan(int originX, int originY, int originZ,
								 double craterRadius, double fireballRadius, double blastRadius,
								 double scorchBand, long seed, boolean vitrifyFloor,
								 boolean evaporateWater, boolean igniteFlammables, boolean griefing) {
		this.originX = originX;
		this.originY = originY;
		this.originZ = originZ;
		this.craterRadius = Math.max(0.0D, craterRadius);
		this.craterDepthRatio = this.craterRadius > 0.5D ? 0.55D : 0.0D;
		this.rimWidthRatio = 0.42D;
		this.rimHeightRatio = 0.16D;
		this.fireballRadius = Math.max(0.0D, fireballRadius);
		this.blastRadius = Math.max(0.0D, blastRadius);
		this.scorchBand = Math.max(0.0D, scorchBand);
		this.seed = seed;
		this.vitrifyFloor = vitrifyFloor;
		this.evaporateWater = evaporateWater;
		this.igniteFlammables = igniteFlammables;
		this.griefing = griefing;

		this.maxReach = Math.max(Math.max(this.craterRadius * (1.0D + rimWidthRatio),
			this.fireballRadius), Math.max(this.blastRadius, scorchBand)) + 2.0D;
		double depth = this.craterRadius * this.craterDepthRatio;
		double rim = this.craterRadius * rimHeightRatio;
		this.minY = MathUtil.clamp((int) Math.floor(originY - depth - 3.0D), MIN_Y, MAX_Y);
		this.maxY = MathUtil.clamp((int) Math.ceil(originY + rim + this.fireballRadius * 0.5D + 3.0D),
			MIN_Y, MAX_Y);
	}

	/** @return true when a chunk's X/Z range can intersect this plan at all. */
	public boolean intersectsChunk(int chunkX, int chunkZ) {
		int r = (int) Math.ceil(maxReach) + 16;
		int cx0 = chunkX << 4;
		int cz0 = chunkZ << 4;
		return Math.abs(MathUtil.clamp(originX, cx0, cx0 + 15) - originX) <= r
			&& Math.abs(MathUtil.clamp(originZ, cz0, cz0 + 15) - originZ) <= r;
	}

	/**
	 * Height of the crater floor (the lowest y still present) for the column at
	 * {@code (dx, dz)} relative to the origin, or {@link Integer#MIN_VALUE} when the
	 * column is untouched. Below the returned value the column is excavated.
	 *
	 * <p>Model: {@code floorY = cy - depth*sqrt(1 - (d/R)^2)} inside the bowl, which is
	 * the lower half of an ellipsoid. Using {@code sqrt} of a clamped ratio makes the lip
	 * vertical-ish at the rim (as real craters are) without a discontinuity in the
	 * derivative that would show up as a visible step in the glass floor.</p>
	 */
	public double bowlFloorY(double horizontalDistance) {
		if (craterRadius <= 0.5D) {
			return Double.NaN;
		}
		double k = MathUtil.clamp01(horizontalDistance / craterRadius);
		if (k >= 1.0D) {
			return Double.NaN;
		}
		double depth = craterRadius * craterDepthRatio;
		return originY - depth * Math.sqrt(Math.max(0.0D, 1.0D - k * k));
	}

	/**
	 * Extra ground height added by the thrown-up rim, in blocks, for a column at
	 * {@code horizontalDistance}. Zero inside the bowl and outside the rim band.
	 */
	public double rimRise(double horizontalDistance) {
		if (craterRadius <= 0.5D) {
			return 0.0D;
		}
		double rimInner = craterRadius * 0.92D;
		double rimOuter = craterRadius * (1.0D + rimWidthRatio);
		if (horizontalDistance <= rimInner || horizontalDistance >= rimOuter) {
			return 0.0D;
		}
		double t = (horizontalDistance - rimInner) / Math.max(0.001D, rimOuter - rimInner);
		// Cosine bump: peaks at the lip, smooth to zero at both ends.
		double bump = 0.5D - 0.5D * Math.cos(t * Math.PI * 2.0D);
		return craterRadius * rimHeightRatio * Math.max(0.0D, bump);
	}

	/** Local context bits supplied by the generator, so the decision stays a pure function. */
	public static final byte CTX_AIR_ABOVE = 1;
	public static final byte CTX_GROUND = 2;
	public static final byte CTX_SAND = 4;
	public static final byte CTX_WATER = 8;
	public static final byte CTX_LEAVES = 16;
	public static final byte CTX_GLASS = 32;
	public static final byte CTX_LOG = 64;
	public static final byte CTX_FLAMMABLE = (byte) 128;

	/**
	 * Decide what should happen at one cell. This is the whole crux of "bounded": the
	 * volumetric part needs only geometry, and the surface part needs only a handful of
	 * neighbour facts which the caller already has, so no heightmap scan is ever needed.
	 *
	 * @param dx    block offset from origin X
	 * @param dy    block offset from origin Y
	 * @param dz    block offset from origin Z
	 * @param flat  precomputed horizontal distance (the caller already has its square)
	 * @param ctx   bitmask of {@code CTX_*} local facts
	 */
	public byte classify(int dx, int dy, int dz, double flat, byte ctx) {
		if (!griefing) {
			return OP_NONE;
		}

		// 1 — excavation & floor vitrification (dominant, closest to the origin)
		if (craterRadius > 0.5D && flat <= craterRadius) {
			double floor = bowlFloorY(flat);
			if (!Double.isNaN(floor)) {
				int floorBlock = (int) Math.round(floor) - originY;
				if (dy < floorBlock) {
					// Dig everything that is not already void.
					return (ctx & CTX_AIR_ABOVE) != 0 && (ctx & (CTX_GROUND | CTX_WATER
						| CTX_LEAVES | CTX_GLASS | CTX_LOG)) == 0 ? OP_NONE : OP_EXCAVATE;
				}
				if (dy == floorBlock) {
					return vitrifyFloor ? OP_VITRIFY_FLOOR : OP_HEAT_STONE;
				}
				if (dy <= floorBlock + 2) {
					return OP_HEAT_STONE;
				}
			}
		}

		// 2 — fireball surface band: transmutation, evaporation, ignition
		if (fireballRadius > 0.5D) {
			double d2 = (double) dx * dx + (double) dy * dy + (double) dz * dz;
			if (d2 <= fireballRadius * fireballRadius) {
				if ((ctx & CTX_WATER) != 0) {
					return evaporateWater ? OP_EVAPORATE : OP_NONE;
				}
				if ((ctx & CTX_SAND) != 0) {
					return OP_VITRIFY_FLOOR;
				}
				if ((ctx & CTX_FLAMMABLE) != 0 && igniteFlammables) {
					return OP_IGNITE;
				}
				if ((ctx & CTX_GLASS) != 0) {
					return OP_BREAK_GLASS;
				}
				if (dy <= 1 && (ctx & CTX_GROUND) != 0) {
					return OP_HEAT_STONE;
				}
				return OP_NONE;
			}
		}

		// 3 — destructive wave band: strip leaves, shatter glass
		if (blastRadius > 0.5D && flat <= blastRadius) {
			if ((ctx & CTX_GLASS) != 0) {
				return OP_BREAK_GLASS;
			}
			if ((ctx & CTX_LEAVES) != 0) {
				return OP_STRIP_LEAVES;
			}
		}

		// 4 — surface scorch on the rim and the outer band (top-most solid blocks only)
		if ((ctx & (CTX_GROUND | CTX_LEAVES | CTX_LOG)) != 0 && (ctx & CTX_AIR_ABOVE) != 0) {
			boolean inRim = flat <= craterRadius * (1.0D + rimWidthRatio);
			if (inRim && dy >= (int) Math.round(rimRise(flat)) - 1) {
				return (ctx & CTX_LEAVES) != 0 ? OP_STRIP_LEAVES : OP_SCORCH;
			}
			if (scorchBand > 0.0D && flat > craterRadius && flat <= craterRadius + scorchBand) {
				return (ctx & CTX_LEAVES) != 0 ? OP_STRIP_LEAVES
					: ((ctx & CTX_LOG) != 0 ? OP_CHARRED_LOG : OP_SCORCH);
			}
		}
		return OP_NONE;
	}

	/** Bounding box of the plan's influence, in block coordinates. */
	public int[] bounds() {
		int r = (int) Math.ceil(maxReach);
		return new int[]{originX - r, minY, originZ - r, originX + r, maxY, originZ + r};
	}

	/** Upper bound on cells the plan may inspect — used for budget planning. */
	public int estimatedCells() {
		int r = (int) Math.ceil(maxReach);
		int h = Math.max(1, maxY - minY + 1);
		return SpatialUtil.estimateShellCells(r, r) * Math.min(h, 2 * r + 1) / 4;
	}

	// ———————————————————————————————————————————————— (de)serialisation

	public void write(java.util.List<Long> out) {
		out.add((long) originX);
		out.add((long) originY);
		out.add((long) originZ);
		out.add(Double.doubleToLongBits(craterRadius));
		out.add(Double.doubleToLongBits(fireballRadius));
		out.add(Double.doubleToLongBits(blastRadius));
		out.add(Double.doubleToLongBits(scorchBand));
		out.add(seed);
		out.add(flagsToLong());
	}

	private long flagsToLong() {
		long f = 0L;
		if (vitrifyFloor) {
			f |= 1L;
		}
		if (evaporateWater) {
			f |= 2L;
		}
		if (igniteFlammables) {
			f |= 4L;
		}
		if (griefing) {
			f |= 8L;
		}
		return f;
	}

	public static DetonationTerrainPlan read(long[] v) {
		if (v == null || v.length < 9) {
			return null;
		}
		long f = v[8];
		return new DetonationTerrainPlan((int) v[0], (int) v[1], (int) v[2],
			Double.longBitsToDouble(v[3]), Double.longBitsToDouble(v[4]),
			Double.longBitsToDouble(v[5]), Double.longBitsToDouble(v[6]), v[7],
			(f & 1L) != 0L, (f & 2L) != 0L, (f & 4L) != 0L, (f & 8L) != 0L);
	}

	@Override
	public String toString() {
		return String.format(java.util.Locale.ROOT,
			"Plan@(%d,%d,%d) crater=%.1f fire=%.1f blast=%.1f scorch=%.1f reach=%.1f y=[%d,%d]",
			originX, originY, originZ, craterRadius, fireballRadius, blastRadius, scorchBand,
			maxReach, minY, maxY);
	}
}
