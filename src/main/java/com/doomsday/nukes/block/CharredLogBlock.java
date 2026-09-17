package com.doomsday.nukes.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.world.BlockView;
import net.minecraft.util.math.BlockPos;

/**
 * A trunk that was inside the fireball and lost. Charred logs keep the verticality of a forest
 * while saying "this burned": standing, black, leafless.
 *
 * <p>Choices that are easy to get wrong, and are therefore explicit:</p>
 * <ul>
 *   <li>It is <b>not</b> a {@code LogBlock} subclass. Reusing log behaviour would let it be
 *       crafted into planks and would keep the leaves-decay/sapling rules, so a burnt forest
 *       would silently regrow its canopy.</li>
 *   <li>It does <b>not</b> spread fire. Charred wood that keeps burning for an hour turns a
 *       cinematic moment into a lag spike and a grief report.</li>
 *   <li>Its sound group is wood (set in {@code ModBlocks}) — the audio is half of why walking
 *       through the aftermath reads correctly.</li>
 * </ul>
 */
public class CharredLogBlock extends Block {
	public CharredLogBlock(Settings settings) {
		super(settings);
	}

	@Override
	public float getAmbientOcclusionLightLevel(BlockState state, BlockView world, BlockPos pos) {
		return 0.72F;
	}
}
