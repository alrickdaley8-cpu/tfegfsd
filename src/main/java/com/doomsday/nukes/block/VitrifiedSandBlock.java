package com.doomsday.nukes.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.world.BlockView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;

/**
 * Vitrified crater floor — "trinitite". Where the fireball touched sand or rock, the surface
 * fused into a dark green-black glass.
 *
 * <p>Three properties are load-bearing for the look:</p>
 * <ul>
 *   <li><b>Low luminance (4)</b> — a crater must be visible at night, otherwise the most
 *       cinematic part of the aftermath disappears after dark. It is deliberately not 15: the
 *       glow should read as cooling glass, not a lamp.</li>
 *   <li><b>Slightly translucent</b> so the light bleeds one block, which is what sells "fresh
 *       slag" without touching the lighting engine.</li>
 *   <li><b>High explosion resistance (12)</b> so a later detonation does not simply erase the
 *       previous one; craters accumulate, which is what makes a nuked biome read as nuked.</li>
 * </ul>
 */
public class VitrifiedSandBlock extends Block {
	public VitrifiedSandBlock(Settings settings) {
		super(settings);
	}

	@Override
	protected boolean isTransparent(BlockState state, BlockView world, BlockPos pos) {
		// Fresh glass crust lets its neighbours light each other through it.
		return true;
	}

	@Override
	public float getAmbientOcclusionLightLevel(BlockState state, BlockView world, BlockPos pos) {
		// 1.0 = fully lit ambient term. Fresh glass does not occlude its neighbours.
		return 1.0F;
	}

	@Override
	public void randomDisplayTick(BlockState state, net.minecraft.world.World world, BlockPos pos,
								  Random random) {
		// Occasional heat shimmer above the slag, but only client-side and only for the block
		// directly under a player-visible air cell, so a 40-block crater does not become a
		// particle emitter.
		if (random.nextFloat() < 0.012F && world.isClient
			&& world.getBlockState(pos.up()).isAir()) {
			world.addParticle(net.minecraft.particle.ParticleTypes.CAMPFIRE_COSY_SMOKE,
				true, pos.getX() + 0.5D + random.nextFloat() * 0.4D - 0.2D,
				pos.getY() + 1.02D, pos.getZ() + 0.5D + random.nextFloat() * 0.4D - 0.2D,
				0.0D, 0.008D, 0.0D);
			world.addParticle(net.minecraft.particle.ParticleTypes.FLAME,
				true, pos.getX() + 0.5D, pos.getY() + 1.01D, pos.getZ() + 0.5D,
				0.0D, 0.01D, 0.0D);
		}
	}
}
