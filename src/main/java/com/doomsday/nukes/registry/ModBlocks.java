package com.doomsday.nukes.registry;

import com.doomsday.nukes.DoomsdayNukes;
import com.doomsday.nukes.block.CharredLogBlock;
import com.doomsday.nukes.block.FlashLightBlock;
import com.doomsday.nukes.block.HeatedStoneBlock;
import com.doomsday.nukes.block.NukeBlock;
import com.doomsday.nukes.block.ScorchedDirtBlock;
import com.doomsday.nukes.block.VitrifiedSandBlock;
import com.doomsday.nukes.detonation.NukePreset;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;

/**
 * All mod blocks.
 *
 * <p>The four devices share one {@link NukeBlock} <em>class</em> but are four distinct
 * {@code Block} instances with distinct IDs. That gives each device its own blockstate, model,
 * texture set and yield profile without putting a variant property in the block state — a
 * metadata-style variant would make the state id space depend on config, and would make
 * "Tsar Bomba" a data value rather than a registry entry you can reference from a datapack,
 * a loot table or a command.</p>
 */
public final class ModBlocks {
	// ————————————————————————————————————————————————— devices (stage 0 hosts)
	public static final NukeBlock STANDARD_NUKE = device(NukePreset.STANDARD_NUKE);
	public static final NukeBlock LITTLE_BOY = device(NukePreset.LITTLE_BOY);
	public static final NukeBlock FAT_MAN = device(NukePreset.FAT_MAN);
	public static final NukeBlock TSAR_BOMBA = device(NukePreset.TSAR_BOMBA);

	/** Vitrified crater floor ("trinitite"). Faintly luminous so the crater reads at night. */
	public static final VitrifiedSandBlock VITRIFIED_SAND = (VitrifiedSandBlock) register(
		"vitrified_sand",
		new VitrifiedSandBlock(AbstractBlock.Settings.create()
			.strength(6.0F, 12.0F)
			.requiresTool()
			.mapColor(MapColor.TEAL)
			.sounds(BlockSoundGroup.GLASS)
			.luminance(state -> 4)));

	/** Short-lived melted rock under a fresh crater floor; reverts to cobblestone by itself. */
	public static final HeatedStoneBlock HEATED_STONE = (HeatedStoneBlock) register(
		"heated_stone",
		new HeatedStoneBlock(AbstractBlock.Settings.create()
			.strength(3.0F, 9.0F)
			.requiresTool()
			.mapColor(MapColor.BLACK)
			.sounds(BlockSoundGroup.STONE)
			// Emissive-style glow: light without needing sky access, so it works at night and in
			// a cave, which is where a hidden bunker test shot is most visible.
			.luminance(state -> 7)));

	/** Charred topsoil — the scorch ring. Hoe-able back to dirt by the player. */
	public static final ScorchedDirtBlock SCORCHED_DIRT = (ScorchedDirtBlock) register(
		"scorched_dirt",
		new ScorchedDirtBlock(AbstractBlock.Settings.create()
			.strength(0.5F)
			.mapColor(MapColor.GRAY)
			.sounds(BlockSoundGroup.GRAVEL)));

	/** Burnt trunk left standing after the fireball passed through a forest. */
	public static final CharredLogBlock CHARRED_LOG = (CharredLogBlock) register(
		"charred_log",
		new CharredLogBlock(AbstractBlock.Settings.create()
			.strength(2.0F, 4.0F)
			.mapColor(MapColor.BLACK)
			.sounds(BlockSoundGroup.WOOD)));

	/**
	 * Invisible 1-tick-scale light emitter: the stage-1 flash light and the dynamic-light
	 * fallback. The vanilla/Sodium light engine propagates it, so nothing in the lighting path
	 * is replaced and Iris keeps working.
	 */
	public static final FlashLightBlock FLASH_LIGHT = (FlashLightBlock) register(
		"flash_light",
		new FlashLightBlock(AbstractBlock.Settings.create()
			.strength(-1.0F, 3600000.0F)
			.noOcclusion()
			.nonOpaque()
			.notSolid()
			.noCollision()
			.dropsNothing()
			.replaceable()
			// luminance is the right knob: block light feeds the engine that already exists.
			// emissiveLighting would additionally suppress sky light for no visual gain here.
			.luminance(state -> 15)));

	private ModBlocks() {
	}

	private static NukeBlock device(NukePreset preset) {
		return (NukeBlock) register(preset.blockId(), new NukeBlock(preset));
	}

	private static Block register(String name, Block block) {
		return Registry.register(Registries.BLOCK, DoomsdayNukes.id(name), block);
	}

	/** Block for a device preset — used by items, commands and the detonator. */
	public static NukeBlock blockFor(NukePreset preset) {
		switch (preset) {
			case LITTLE_BOY:
				return LITTLE_BOY;
			case FAT_MAN:
				return FAT_MAN;
			case TSAR_BOMBA:
				return TSAR_BOMBA;
			case STANDARD_NUKE:
			default:
				return STANDARD_NUKE;
		}
	}

	public static NukePreset presetOf(Block block) {
		return block instanceof NukeBlock nuke ? nuke.preset() : NukePreset.STANDARD_NUKE;
	}

	/** True for anything this mod generated as blast aftermath, used by the recovery command. */
	public static boolean isAftermath(Block block) {
		return block == VITRIFIED_SAND || block == HEATED_STONE || block == SCORCHED_DIRT
			|| block == CHARRED_LOG || block == FLASH_LIGHT;
	}
}
