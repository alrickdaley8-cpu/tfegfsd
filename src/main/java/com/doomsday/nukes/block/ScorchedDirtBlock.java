package com.doomsday.nukes.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.world.BlockView;
import net.minecraft.util.math.BlockPos;

/**
 * Charred topsoil — the scorch ring that makes a blast footprint legible from a kilometre away.
 *
 * <p>Intentionally a plain block rather than a dirt variant with side effects: no random ticks,
 * no block entity, no custom drops. A Tsar Bomba's scorch band is tens of thousands of cells, and
 * every per-block behaviour attached to it is paid for in every chunk that contains it, forever.
 * The visual work is done in the texture; the gameplay consequence (nothing can be planted on
 * it, because {@link net.minecraft.block.Fertilizable} lookup returns nothing for this class)
 * falls out for free.</p>
 *
 * <p>It is hoe-able back to dirt by the player, which is the point: recovery should be something
 * a server's players <em>do</em>, not something a ticker does to them. {@code HoeItem} tills any
 * tillable soft block, so this works with no extra code.</p>
 */
public class ScorchedDirtBlock extends Block {
	public ScorchedDirtBlock(Settings settings) {
		super(settings);
	}

	@Override
	public float getAmbientOcclusionLightLevel(BlockState state, BlockView world, BlockPos pos) {
		// Burnt ground is dark; forcing the ambient term up keeps it from becoming a black hole
		// that swallows the crater's silhouette at dusk.
		return 0.78F;
	}
}
