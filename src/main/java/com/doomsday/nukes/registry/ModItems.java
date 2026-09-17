package com.doomsday.nukes.registry;

import com.doomsday.nukes.DoomsdayNukes;
import com.doomsday.nukes.detonation.NukePreset;
import com.doomsday.nukes.item.GeigerCounterItem;
import com.doomsday.nukes.item.HazmatGogglesItem;
import com.doomsday.nukes.item.IodineTabletItem;
import com.doomsday.nukes.item.NukeItem;
import com.doomsday.nukes.item.RemoteDetonatorItem;
import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Rarity;

/**
 * All mod items, registered against {@link Registries#ITEM}.
 *
 * <p>Each device block gets a matching {@link NukeItem} whose only extra job is to remember
 * which preset it belongs to, so a placed device and the item that placed it can never disagree
 * about yield. Equipment items ({@code HazmatGogglesItem}) deliberately implement protection as
 * <em>data</em> queried by the effects system ({@code HazmatGear}) rather than as an
 * {@code EquipmentListener} or attribute mixin — one place decides protection, and it is not
 * the item.</p>
 */
public final class ModItems {
	// ————————————————————————————————————————————————— device items (BlockItem per preset)
	public static final NukeItem STANDARD_NUKE = nukeItem(NukePreset.STANDARD_NUKE);
	public static final NukeItem LITTLE_BOY = nukeItem(NukePreset.LITTLE_BOY);
	public static final NukeItem FAT_MAN = nukeItem(NukePreset.FAT_MAN);
	public static final NukeItem TSAR_BOMBA = nukeItem(NukePreset.TSAR_BOMBA);

	// —————————————————————————————————————————————————————— equipment
	public static final HazmatGogglesItem HAZMAT_GOGGLES = (HazmatGogglesItem) register(
		"hazmat_goggles",
		new HazmatGogglesItem(new Item.Settings()
			.maxDamage(336)
			.rarity(Rarity.UNCOMMON)));

	public static final GeigerCounterItem GEIGER_COUNTER = (GeigerCounterItem) register(
		"geiger_counter",
		new GeigerCounterItem(new Item.Settings()
			.maxCount(1)
			.rarity(Rarity.RARE)));

	public static final RemoteDetonatorItem REMOTE_DETONATOR = (RemoteDetonatorItem) register(
		"remote_detonator",
		new RemoteDetonatorItem(new Item.Settings()
			.maxCount(1)
			.rarity(Rarity.RARE)));

	public static final IodineTabletItem IODINE_TABLET = (IodineTabletItem) register(
		"iodine_tablet",
		new IodineTabletItem(new Item.Settings()
			.maxCount(16)));

	// ———————————————————————————————————————————————————— aftermath blocks
	public static final BlockItem VITRIFIED_SAND = blockItem(ModBlocks.VITRIFIED_SAND);
	public static final BlockItem HEATED_STONE = blockItem(ModBlocks.HEATED_STONE);
	public static final BlockItem SCORCHED_DIRT = blockItem(ModBlocks.SCORCHED_DIRT);
	public static final BlockItem CHARRED_LOG = blockItem(ModBlocks.CHARRED_LOG);

	private ModItems() {
	}

	private static NukeItem nukeItem(NukePreset preset) {
		return (NukeItem) register(preset.blockId(),
			new NukeItem(ModBlocks.blockFor(preset), preset,
				new Item.Settings().maxCount(16)));
	}

	private static BlockItem blockItem(Block block) {
		String path = Registries.BLOCK.getId(block).getPath();
		return (BlockItem) register(path, new BlockItem(block, new Item.Settings()));
	}

	private static Item register(String name, Item item) {
		return Registry.register(Registries.ITEM, DoomsdayNukes.id(name), item);
	}

	public static ItemStack stackOf(NukePreset preset) {
		switch (preset) {
			case LITTLE_BOY:
				return new ItemStack(LITTLE_BOY);
			case FAT_MAN:
				return new ItemStack(FAT_MAN);
			case TSAR_BOMBA:
				return new ItemStack(TSAR_BOMBA);
			case STANDARD_NUKE:
			default:
				return new ItemStack(STANDARD_NUKE);
		}
	}

	public static NukeItem itemFor(NukePreset preset) {
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

	/** Called from the entrypoint so ordering is explicit. */
	public static void register() {
		if (STANDARD_NUKE == null || HAZMAT_GOGGLES == null) {
			throw new IllegalStateException("Item registration failed");
		}
	}

	/** Convenience for the recipe/loot JSON validator path. */
	public static ItemGroup getCreativeTabKey() {
		return ModItemGroups.TAB;
	}
}
