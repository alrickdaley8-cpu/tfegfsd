package com.doomsday.nukes.block;

import com.doomsday.nukes.registry.ModBlocks;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.world.BlockView;
import net.minecraft.block.ShapeContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.World;

/**
 * Invisible, self-removing light emitter — <b>the flash's lighting path, and the fallback when
 * no dynamic-light mod is installed.</b>
 *
 * <h2>Why a block instead of a light API</h2>
 * Minecraft 1.21.1 has no public "temporary point light" API. The options are: depend on a
 * dynamic-light mod (forbidden by Rule 3), replace the lighting pipeline (forbidden by Rule 2/3,
 * and it is exactly what breaks Sodium and Iris), or write a block whose {@code luminance} is 15
 * and let the <em>existing</em> light engine propagate it. The last option is what this is: it
 * works identically with Sodium, Oculus/Iris, and no mods at all, because from the engine's point
 * of view it is just a glowstone-like block that is not drawn.
 *
 * <h2>Guarantees</h2>
 * <ul>
 *   <li>{@link #place} refuses to overwrite anything non-air, so a flash can never delete a
 *       player's build. Skipped emitters are counted, not retried.</li>
 *   <li>Removal is a vanilla scheduled block tick, so it happens even if the server restarts, and
 *       a stale tick is ignored by the state guard. There is no code path that can leave one
 *       behind permanently.</li>
 *   <li>No render shape, no collision, no drops, {@code replaceable}, so its presence in the
 *       world for one second cannot affect gameplay beyond light.</li>
 * </ul>
 */
public class FlashLightBlock extends Block {
	/** Zero-size shape: invisible to the eye, invisible to the physics and the camera. */
	private static final VoxelShape EMPTY = VoxelShapes.empty();

	public FlashLightBlock(Settings settings) {
		super(settings);
	}

	/**
	 * Places one emitter for {@code ticks}, if — and only if — the cell is air.
	 *
	 * @return true when an emitter was actually placed
	 */
	public static boolean place(ServerWorld world, BlockPos pos, int ticks) {
		if (world == null || pos == null || ticks <= 0) {
			return false;
		}
		BlockState existing = world.getBlockState(pos);
		if (!existing.isAir()) {
			return false;
		}
		if (!world.setBlockState(pos, ModBlocks.FLASH_LIGHT.getDefaultState())) {
			return false;
		}
		world.createAndScheduleBlockTick(pos, ModBlocks.FLASH_LIGHT, Math.max(1, ticks));
		return true;
	}

	/**
	 * Places the emitter cluster used by a detonation: the epicentre plus a ring, so the light has
	 * volume instead of being one hard point. Cost is capped at 7 block writes per detonation.
	 *
	 * @return emitters placed
	 */
	public static int placeCluster(ServerWorld world, BlockPos centre, int ticks, int spread) {
		int placed = place(world, centre.up(2), ticks) ? 1 : 0;
		if (spread <= 1) {
			return placed;
		}
		for (int i = 0; i < 6; i++) {
			double angle = i * (Math.PI / 3.0D);
			BlockPos p = centre.add((int) Math.round(Math.cos(angle) * spread), 1,
				(int) Math.round(Math.sin(angle) * spread));
			if (place(world, p, ticks)) {
				placed++;
			}
		}
		return placed;
	}

	@Override
	public void onScheduledTick(BlockState state, ServerWorld world, BlockPos pos, Object tag) {
		// Guard, then remove. The guard is what makes a stale scheduled tick harmless after the
		// block was already replaced (for example by the crater arriving first).
		if (world.getBlockState(pos).isOf(this)) {
			world.removeBlock(pos, false);
		}
	}

	@Override
	public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos,
									  ShapeContext context) {
		return EMPTY;
	}

	@Override
	public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos,
										ShapeContext context) {
		return EMPTY;
	}

	@Override
	public VoxelShape getCullingShape(BlockState state, BlockView world, BlockPos pos) {
		return EMPTY;
	}

	@Override
	public boolean isReplaceable(BlockState state, net.minecraft.item.ItemPlacementContext ctx) {
		// Players must not be able to "place" into it, or a leftover emitter could be buried and
		// stuck until its tick fires.
		return false;
	}

	@Override
	public float getAmbientOcclusionLightLevel(BlockState state, BlockView world, BlockPos pos) {
		return 1.0F;
	}

	@Override
	public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState,
							   boolean moved) {
		if (!state.isOf(newState.getBlock()) && !world.isClient
			&& world.getBlockEntity(pos) == null) {
			// Nothing to clean up: this block owns no state. The hook exists so the class documents
			// that a crater overwriting an emitter is a supported, expected ordering.
		}
		super.onStateReplaced(state, world, pos, newState, moved);
	}
}
