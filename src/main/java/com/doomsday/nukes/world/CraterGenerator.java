package com.doomsday.nukes.world;

import com.doomsday.nukes.DoomsdayNukes;
import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.config.DoomsdayConfig;
import com.doomsday.nukes.detonation.NukePreset;
import com.doomsday.nukes.registry.ModBlocks;
import com.doomsday.nukes.util.MathUtil;
import com.doomsday.nukes.util.SpatialUtil;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MutablePos;
import net.minecraft.world.World;

/**
 * Stage 2/5 terrain engine: turns a {@link DetonationTerrainPlan} into real block edits.
 *
 * <h2>Structure of the generated crater</h2>
 * <table border="1">
 *   <tr><th>Zone</th><th>Extent</th><th>Result</th></tr>
 *   <tr><td>Core</td><td>{@code d &lt; 0.55R}</td>
 *       <td>excavated to the bowl floor; floor cell becomes
 *       {@code doomsday:vitrified_sand} (trinitite) and glows faintly; the two cells under
 *       it become {@code doomsday:heated_stone} which reverts to plain stone after
 *       {@code heatedStoneSeconds}</td></tr>
 *   <tr><td>Lip</td><td>{@code 0.55R … R}</td>
 *       <td>partial excavation, glass on sand columns, heated rock elsewhere</td></tr>
 *   <tr><td>Rim</td><td>{@code R … 1.42R}</td>
 *       <td>no removal; the top solid block is scorched, exposed leaves are stripped,
 *       exposed logs are charred</td></tr>
 *   <tr><td>Fireball band</td><td>{@code &lt;= fireballRadius}</td>
 *       <td>sand → glass, water → air (+ client steam), flammables → fire, glass → broken</td></tr>
 *   <tr><td>Wave band</td><td>{@code &lt;= blastRadius}</td>
 *       <td>glass shattered, leaves stripped — the "standing forest, no canopy" look</td></tr>
 *   <tr><td>Scorch ring</td><td>{@code R … R + scorchBand}</td>
 *       <td>charred dirt / charred logs / burned vegetation</td></tr>
 * </table>
 *
 * <h2>Cost control</h2>
 * Work is enumerated <b>per chunk</b> and only for chunks that are loaded; everything else
 * becomes a pending plan. Each chunk pass is capped by {@code budget} cells so a single
 * {@code applyChunk} call is bounded regardless of how silly the config is. The generator
 * never loads a chunk, never uses a heightmap, and never writes a block whose current
 * state already matches.
 */
public final class CraterGenerator {
	/** Cells inspected per chunk pass, independent of the write budget. */
	private static final int MAX_CELLS_INSPECTED_PER_CHUNK = 46_000;

	private CraterGenerator() {
	}

	// ——————————————————————————————————————————————————— plan build

	/**
	 * Derives the terrain plan from the resolved tuning. Returns {@code null} when nothing
	 * in the configuration asks for a terrain change, which lets the caller skip all work.
	 */
	public static DetonationTerrainPlan buildPlan(net.minecraft.util.math.Vec3d origin,
												 ConfigManager.ResolvedTuning tuning,
												 double yieldKt, DoomsdayConfig c) {
		if (!c.griefingEnabled) {
			return null;
		}
		double crater = c.craterEnabled ? tuning.craterRadius() : 0.0D;
		double fireball = c.fireballTerrifiesTerrain ? tuning.fireballRadius() : 0.0D;
		double blast = c.shockwaveDestructive ? tuning.blastRadius() : 0.0D;
		if (crater < 0.5D && fireball < 0.5D && blast < 0.5D && c.scorchBandBlocks < 0.5D) {
			return null;
		}
		long seed = com.doomsday.nukes.util.MathUtil.hash(
			(int) origin.x, (int) origin.y, (int) origin.z) * 0x9E3779B1L
			+ (long) (yieldKt * 1000.0D);
		return new DetonationTerrainPlan(
			(int) Math.floor(origin.x), (int) Math.round(origin.y), (int) Math.floor(origin.z),
			crater, fireball, blast, c.scorchBandBlocks, seed,
			c.vitrifyCraterFloor, c.evaporateWater, c.igniteFlammables, true);
	}

	// ————————————————————————————————————————————————— chunk dispatch

	/**
	 * Enqueues every cell the plan touches, chunk by chunk, for loaded chunks only.
	 * Unloaded chunks are recorded in {@link DoomsdayWorldData} so the crater finishes
	 * itself when the player walks towards them.
	 *
	 * @return cells queued
	 */
	public static int enqueue(ServerWorld world, TerrainWorkQueue queue, DetonationTerrainPlan plan) {
		if (plan == null) {
			return 0;
		}
		int[] b = plan.bounds();
		int c0x = b[0] >> 4;
		int c0z = b[2] >> 4;
		int c1x = b[3] >> 4;
		int c1z = b[5] >> 4;
		int chunks = SpatialUtil.chunksSpanned(b[0], b[3]) * SpatialUtil.chunksSpanned(b[2], b[5]);
		int budget = Math.max(1, queue.remaining() == 0
			? ConfigManager.get().terrainBlocksPerTick * Math.max(1, ConfigManager.get().terrainMaxTicks)
			: TerrainWorkQueue.MAX_PENDING_EDITS);
		int perChunkBudget = Math.max(400, budget / Math.max(1, Math.min(chunks,
			Math.max(1, ConfigManager.get().maxChunksTouched))));

		int queued = 0;
		DoomsdayWorldData data = DoomsdayWorldData.get(world);
		boolean hasDeferred = false;
		for (int cz = c0z; cz <= c1z; cz++) {
			for (int cx = c0x; cx <= c1x; cx++) {
				if (!plan.intersectsChunk(cx, cz)) {
					continue;
				}
				if (!world.isChunkLoaded(cx, cz)) {
					if (!hasDeferred) {
						hasDeferred = true;
					}
					data.addPendingChunk(cx, cz, plan);
					continue;
				}
				queued += applyChunk(world, plan, cx, cz, queue, perChunkBudget);
				if (queue.isFull()) {
					DoomsdayNukes.LOGGER.warn(
						"Terrain queue saturated at {} cells; remaining crater area will finish on retry.", queued);
					data.markDirty();
					return queued;
				}
			}
		}
		if (hasDeferred && ConfigManager.get().verboseLogging) {
			DoomsdayNukes.LOGGER.info("Crater plan deferred for unloaded chunks (resumes on load).");
		}
		data.markDirty();
		return queued;
	}

	/**
	 * Fills the queue for exactly one chunk. Also the entry point used when a chunk
	 * finishes loading, which is why it takes explicit chunk coordinates and a budget.
	 *
	 * @return number of edits queued
	 */
	public static int applyChunk(World world, DetonationTerrainPlan plan, int chunkX, int chunkZ,
								 TerrainWorkQueue queue, int budget) {
		int[] b = plan.bounds();
		int x0 = Math.max(b[0], chunkX << 4);
		int x1 = Math.min(b[3], (chunkX << 4) + 15);
		int z0 = Math.max(b[2], chunkZ << 4);
		int z1 = Math.min(b[5], (chunkZ << 4) + 15);
		int y0 = b[1];
		int y1 = b[4];
		if (x1 < x0 || z1 < z0 || y1 < y0) {
			return 0;
		}

		int queued = 0;
		int inspected = 0;
		final MutablePos pos = new MutablePos(0, 0, 0);
		final MutablePos above = new MutablePos(0, 0, 0);

		for (int x = x0; x <= x1; x++) {
			int dx = x - plan.originX;
			for (int z = z0; z <= z1; z++) {
				int dz = z - plan.originZ;
				double flat = Math.sqrt((double) dx * dx + (double) dz * dz);
				if (flat > plan.maxReach) {
					continue;
				}
				above.set(x, y1 + 1, z);
				int topY = y1;
				for (int y = y0; y <= topY; y++) {
					if (++inspected > MAX_CELLS_INSPECTED_PER_CHUNK) {
						return queued;
					}
					pos.set(x, y, z);
					BlockState state = world.getBlockState(pos);
					if (state.isAir()) {
						continue;
					}
					byte ctx = contextOf(world, pos, above, state);
					int dy = y - plan.originY;
					byte op = plan.classify(dx, dy, dz, flat, ctx);
					if (op == DetonationTerrainPlan.OP_NONE) {
						continue;
					}
					BlockState next = stateFor(op, state, world, pos, plan, dx, dy, dz);
					if (next == null || next == state) {
						continue;
					}
					if (queue.add(x, y, z, next)) {
						queued++;
					}
				}
			}
		}
		return queued;
	}

	// ———————————————————————————————————————————————————— decisions

	/** Packs the local facts {@link DetonationTerrainPlan#classify} needs, in 2 block reads. */
	private static byte contextOf(World world, MutablePos pos, MutablePos above, BlockState state) {
		byte ctx = 0;
		above.set(pos.getX(), pos.getY() + 1, pos.getZ());
		if (world.getBlockState(above).isAir()) {
			ctx |= DetonationTerrainPlan.CTX_AIR_ABOVE;
		}
		boolean sand = state.isIn(BlockTags.SAND);
		if (sand || state.isIn(BlockTags.DIRT) || isRock(state)) {
			ctx |= DetonationTerrainPlan.CTX_GROUND;
		}
		if (sand) {
			ctx |= DetonationTerrainPlan.CTX_SAND;
		}
		if (!state.getFluidState().isEmpty()) {
			ctx |= DetonationTerrainPlan.CTX_WATER;
		}
		if (state.isIn(BlockTags.LEAVES)) {
			ctx |= DetonationTerrainPlan.CTX_LEAVES | DetonationTerrainPlan.CTX_FLAMMABLE;
		}
		if (state.isIn(BlockTags.LOGS)) {
			ctx |= DetonationTerrainPlan.CTX_LOG | DetonationTerrainPlan.CTX_FLAMMABLE;
		}
		if (state.isIn(BlockTags.PLANKS) || state.isIn(BlockTags.WOOL)) {
			ctx |= DetonationTerrainPlan.CTX_FLAMMABLE;
		}
		if (isGlass(state)) {
			ctx |= DetonationTerrainPlan.CTX_GLASS;
		}
		return ctx;
	}

	/** True for stone-like material that can be thermally altered rather than removed. */
	static boolean isRock(BlockState state) {
		if (state.isAir() || !state.getFluidState().isEmpty()) {
			return false;
		}
		if (state.isIn(BlockTags.SAND) || state.isIn(BlockTags.DIRT)
			|| state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.LOGS)
			|| state.isIn(BlockTags.PLANKS)) {
			return false;
		}
		float hardness = state.getBlock().getHardness();
		return hardness >= 1.4F && hardness <= 60.0F;
	}

	static boolean isGlass(BlockState state) {
		return state.getBlock() instanceof net.minecraft.block.GlassBlock || state.isOf(Blocks.GLASS_PANE);
	}

	/**
	 * Ignition guard: fire can only be placed where the block itself was removed-by-burn
	 * and the cell above is empty. Returns the state to write (fire) or null.
	 */
	static BlockState ignitionTarget(World world, BlockPos pos, BlockState current) {
		if (!world.getBlockState(pos.up()).isAir()) {
			// No air above: the cell is buried, so the flame would be instantly smothered
			// and the block edit would be pure waste.
			return null;
		}
		if (current.isIn(BlockTags.LEAVES)) {
			// Canopy is consumed outright and replaced by open flame.
			return Blocks.FIRE.getDefaultState();
		}
		if (current.isIn(BlockTags.LOGS)) {
			// Trunks char in place. Replacing a load-bearing log with fire collapses the
			// whole tree every time, which reads as a bug, not as an explosion.
			return ModBlocks.CHARRED_LOG.getDefaultState();
		}
		if (current.isIn(BlockTags.PLANKS) || current.isIn(BlockTags.WOOL)) {
			return Blocks.FIRE.getDefaultState();
		}
		return null;
	}

	/** Maps an operation code to the block state to write, or null for "leave alone". */
	private static BlockState stateFor(byte op, BlockState current, World world, BlockPos pos,
									   DetonationTerrainPlan plan, int dx, int dy, int dz) {
		switch (op) {
			case DetonationTerrainPlan.OP_EXCAVATE:
				return Blocks.AIR.getDefaultState();
			case DetonationTerrainPlan.OP_EVAPORATE:
				return Blocks.AIR.getDefaultState();
			case DetonationTerrainPlan.OP_VITRIFY_FLOOR:
				return ModBlocks.VITRIFIED_SAND.getDefaultState();
			case DetonationTerrainPlan.OP_HEAT_STONE:
				return isRock(current) ? ModBlocks.HEATED_STONE.getDefaultState() : null;
			case DetonationTerrainPlan.OP_SCORCH:
				return current.isIn(BlockTags.DIRT) || current.isIn(BlockTags.SAND) || isRock(current)
					? ModBlocks.SCORCHED_DIRT.getDefaultState() : null;
			case DetonationTerrainPlan.OP_CHARRED_LOG:
				return ModBlocks.CHARRED_LOG.getDefaultState();
			case DetonationTerrainPlan.OP_STRIP_LEAVES:
				return current.isIn(BlockTags.LEAVES) ? Blocks.AIR.getDefaultState() : null;
			case DetonationTerrainPlan.OP_BREAK_GLASS:
				return isGlass(current) ? Blocks.AIR.getDefaultState() : null;
			case DetonationTerrainPlan.OP_IGNITE:
				return ignitionTarget(world, pos, current);
			default:
				return null;
		}
	}

	/**
	 * Emergency path: an unmappable op leaves the block alone rather than throwing inside a
	 * server tick, because a crashed tick is worse than one un-scorched block.
	 */
	static BlockState fallback(BlockState current) {
		return current == null ? Blocks.AIR.getDefaultState() : current;
	}
}
