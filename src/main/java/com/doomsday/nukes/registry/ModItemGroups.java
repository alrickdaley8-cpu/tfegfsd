package com.doomsday.nukes.registry;

import com.doomsday.nukes.DoomsdayNukes;
import com.doomsday.nukes.detonation.NukePreset;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * The creative tab. Kept small and ordered by workflow (place a device → protect yourself →
 * measure → treat), which is how the mod is actually tested.
 */
public final class ModItemGroups {
	public static final RegistryKey<ItemGroup> KEY =
		RegistryKey.of(RegistryKeys.ITEM_GROUP, DoomsdayNukes.id("main"));

	public static final ItemGroup TAB = Registry.register(Registries.ITEM_GROUP, KEY,
		FabricItemGroup.builder()
			.icon(() -> new ItemStack(ModItems.STANDARD_NUKE))
			.displayName(Text.translatable("itemGroup.doomsday.main"))
			.entries((enabledFeatures, entries) -> {
				for (NukePreset preset : NukePreset.values()) {
					entries.add(ModItems.itemFor(preset));
				}
				entries.add(ModItems.REMOTE_DETONATOR);
				entries.add(ModItems.GEIGER_COUNTER);
				entries.add(ModItems.HAZMAT_GOGGLES);
				entries.add(ModItems.IODINE_TABLET);
				entries.add(new ItemStack(ModBlocks.VITRIFIED_SAND));
				entries.add(new ItemStack(ModBlocks.HEATED_STONE));
				entries.add(new ItemStack(ModBlocks.SCORCHED_DIRT));
				entries.add(new ItemStack(ModBlocks.CHARRED_LOG));
			})
			.build());

	private ModItemGroups() {
	}

	public static void register() {
		if (TAB == null) {
			throw new IllegalStateException("Creative tab failed to register");
		}
	}

	/** Exposed for the config screen's "open tab" button. */
	public static Identifier id() {
		return Registries.ITEM_GROUP.getId(TAB);
	}
}
