package com.doomsday.nukes.item;

import com.doomsday.nukes.DoomsdayNukes;
import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.config.DoomsdayConfig;
import com.doomsday.nukes.detonation.NukePreset;
import com.doomsday.nukes.registry.ModBlocks;
import com.doomsday.nukes.util.DText;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;

/**
 * The placeable device item. It is a {@link BlockItem} so all of vanilla's placement rules apply
 * unchanged (sneak-placement, block collision, adventure mode, dispensers via the block item's
 * own behaviour), and it adds exactly two things:
 *
 * <ol>
 *   <li><b>The preset travels with the item.</b> The block instance is chosen from the preset, so
 *       item, block, blockstate, model and detonation profile can never disagree.</li>
 *   <li><b>A placed device is never pre-armed.</b> The item carries no timer, and breaking a
 *       device drops a clean stack from its loot table, so an armed countdown can never be stored
 *       in an inventory, a chest or a hopper. That removes a whole class of "player logged out
 *       with a live nuke in their pack" desync.</li>
 * </ol>
 */
public class NukeItem extends BlockItem {
	private final NukePreset preset;

	public NukeItem(Block block, NukePreset preset, Settings settings) {
		super(block, settings);
		this.preset = preset;
	}

	public NukePreset preset() {
		return preset;
	}

	@Override
	protected boolean placeBlock(ItemPlacementContext ctx, BlockState state, BlockPos pos,
								  net.minecraft.world.World world, net.minecraft.block.entity.BlockEntity be) {
		boolean placed = super.placeBlock(ctx, state, pos, world, be);
		if (placed && !world.isClient) {
			world.playSound(null, pos, SoundEvents.BLOCK_METAL_PLACE, SoundCategory.BLOCKS,
				0.7F, 0.55F);
			if (ConfigManager.get().verboseLogging) {
				DoomsdayNukes.LOGGER.info("Device {} placed at {}", preset.name(), pos.toShortString());
			}
		}
		return placed;
	}

	/**
	 * Tooltip line with the resolved yield profile. Uses the config, so what the tooltip says is
	 * what a detonation will actually do after every multiplier and override.
	 */
	@Override
	public void appendTooltip(ItemStack stack, net.minecraft.item.Item.TooltipContext context,
							 List<Text> tooltip, net.minecraft.item.tooltip.TooltipType type) {
		DoomsdayConfig c = ConfigManager.get();
		if (c.devices.containsKey(preset.configKey)) {
			ConfigManager.ResolvedTuning t = ConfigManager.tuning(preset);
			tooltip.add(DText.of("item.doomsday.nuke.yield",
				String.format(java.util.Locale.ROOT, "%.1f", t.yieldKt())));
			tooltip.add(DText.of("item.doomsday.nuke.blast", (int) Math.round(t.blastRadius())));
			tooltip.add(DText.of("item.doomsday.nuke.timer",
				c.minTimerSeconds, c.maxTimerSeconds));
		}
	}
}
