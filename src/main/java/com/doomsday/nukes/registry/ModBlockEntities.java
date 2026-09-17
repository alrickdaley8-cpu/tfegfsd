package com.doomsday.nukes.registry;

import com.doomsday.nukes.DoomsdayNukes;
import com.doomsday.nukes.block.entity.NukeBlockEntity;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

/**
 * Block entity types.
 *
 * <p><b>One type for all four devices.</b> The preset is recovered from the block state, so the
 * type does not need to be per-device — which matters because every extra {@code BlockEntityType}
 * is another entry the chunk serializer walks for every block entity in every chunk. This is
 * also why there is no block entity for crater aftermath blocks: those are thousands of cells and
 * would otherwise add thousands of permanent objects.</p>
 */
public final class ModBlockEntities {
	public static final BlockEntityType<NukeBlockEntity> NUKE = Registry.register(
		Registries.BLOCK_ENTITY_TYPE,
		DoomsdayNukes.id("nuke"),
		FabricBlockEntityTypeBuilder.create(NukeBlockEntity::new,
			ModBlocks.STANDARD_NUKE,
			ModBlocks.LITTLE_BOY,
			ModBlocks.FAT_MAN,
			ModBlocks.TSAR_BOMBA)
			.build());

	private ModBlockEntities() {
	}

	/** Called from {@code DoomsdayNukes} so class-init ordering stays explicit. */
	public static void register() {
		// Registration happens in the static initialiser; this method exists so the initialiser's
		// execution point is visible in the mod entrypoint rather than hidden behind class loading.
		if (NUKE == null) {
			throw new IllegalStateException("Nuke block entity type failed to register");
		}
	}
}
