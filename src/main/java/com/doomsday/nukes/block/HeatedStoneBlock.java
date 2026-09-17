package com.doomsday.nukes.block;

import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.config.DoomsdayConfig;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.world.BlockView;
import net.minecraft.block.Blocks;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

/**
 * Briefly melted rock under a fresh crater floor: the "stone → heated state where practical"
 * requirement, implemented as a real, <em>self-healing</em> block.
 *
 * <p>The block glows (luminance 7), throws off embers, and on a single vanilla scheduled block
 * tick reverts to {@code cobblestone} — cooled, cracked rock. Choosing a scheduled tick rather
 * than a ticker or a block-entity means:</p>
 * <ul>
 *   <li>zero per-tick cost while the rock is hot,</li>
 *   <li>the revert survives a restart and a chunk unload/reload (the tick store is chunk data),
 *       so a hot block can never become a permanently glowing monument,</li>
 *   <li>no block entity is added to a chunk that may contain thousands of these cells — which
 *       for a Tsar Bomba crater would be tens of thousands of BEs, exactly what Rule 5 forbids.</li>
 * </ul>
 */
public class HeatedStoneBlock extends Block {
	public HeatedStoneBlock(AbstractBlock.Settings settings) {
		super(settings);
	}

	@Override
	public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState,
							 boolean moved) {
		if (world.isClient || oldState.isOf(state.getBlock())) {
			return;
		}
		int life = Math.max(20, DoomsdayConfig.ticks(ConfigManager.get().heatedStoneSeconds));
		((ServerWorld) world).scheduleBlockTick(pos, this, life);
	}

	@Override
	public void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
		// Guard first: a stale tick for a block that has since been replaced must be ignored,
		// which is cheaper and race-free compared with unscheduling on every replacement.
		if (!world.getBlockState(pos).isOf(this)) {
			return;
		}
		world.setBlockState(pos, Blocks.COBBLESTONE.getDefaultState());
	}

	@Override
	public void randomDisplayTick(BlockState state, World world, BlockPos pos, Random random) {
		if (!world.isClient || !world.getBlockState(pos.up()).isAir()) {
			return;
		}
		if (random.nextFloat() < 0.08F) {
			world.addParticle(ParticleTypes.LAVA,
				pos.getX() + 0.5D + random.nextDouble() - 0.5D, pos.getY() + 1.01D,
				pos.getZ() + 0.5D + random.nextDouble() - 0.5D,
				0.0D, 0.01D, 0.0D);
		} else if (random.nextFloat() < 0.05F) {
			world.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE,
				pos.getX() + 0.5D, pos.getY() + 1.05D, pos.getZ() + 0.5D,
				0.0D, 0.02D, 0.0D);
		}
	}

	@Override
	public float getAmbientOcclusionLightLevel(BlockState state, BlockView world, BlockPos pos) {
		// Melted rock reads brighter than the stone around it.
		return 1.0F;
	}
}
