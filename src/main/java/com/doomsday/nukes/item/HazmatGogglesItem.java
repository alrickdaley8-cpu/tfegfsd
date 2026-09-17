package com.doomsday.nukes.item;

import com.doomsday.nukes.registry.ModItems;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ArmorMaterials;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

/**
 * Hazmat Goggles — the head slot of the protective set.
 *
 * <h2>What they actually do</h2>
 * <ul>
 *   <li><b>80 % radiation resistance</b> (config {@code goggleRadiationMultiplier = 0.2}), applied
 *       as a multiplier on incoming dose through {@link HazmatGear}. Not absolute.</li>
 *   <li><b>Flash blindness suppression</b>: the Blindness window is cut to the configured
 *       goggled value and additionally scaled by 0.35, so looking at a detonation at 40 blocks
 *       still costs you your vision for a moment.</li>
 *   <li><b>A HUD indicator</b> while protection is active, and a screen-edge lens overlay so the
 *       equipment is felt in the frame, not only in a number.</li>
 * </ul>
 *
 * <h2>Model/texture strategy</h2>
 * The item uses its own model and texture
 * ({@code assets/doomsday/models/item/hazmat_goggles.json}, 16× inventory sprite). While worn, the
 * helmet layer reuses the vanilla leather armour layer with a fixed dye colour
 * ({@link DataComponentTypes#DYED_COLOR}) — the yellow-green hazmat tint. That is a deliberate
 * trade: a bespoke {@code ArmorMaterial} would need this mod to own the armour-texture pipeline
 * and the {@code Material} interface churns between minor versions, whereas dyeing is stable data.
 * The visual identity a player actually notices — the HUD lens frame and the tint — is entirely
 * ours. See README §"Swapping the worn armour model" for dropping in a full custom layer without
 * touching code.
 */
public class HazmatGogglesItem extends ArmorItem {
	/** Hazard yellow-green, matching the block textures' warning palette. */
	public static final int TINT_RGB = 0x9ACD32;

	public HazmatGogglesItem(Item.Settings settings) {
		super(ArmorMaterials.LEATHER, ArmorItem.Type.HELMET,
			settings.component(DataComponentTypes.DYED_COLOR, new DyedColorComponent(TINT_RGB, false)));
	}

	/** Whether {@code stack} is a pair of goggles — used by the HUD and by the Geiger driver. */
	public static boolean isGoggles(ItemStack stack) {
		return !stack.isEmpty() && stack.getItem() instanceof HazmatGogglesItem;
	}

	public Text brokenMessage() {
		return Text.translatable("item.doomsday.hazmat_goggles.broken")
			.formatted(Formatting.RED, Formatting.ITALIC);
	}

	// Durability is entirely vanilla: the stack is created with maxDamage(336), the thermal wear
	// is applied by HazmatGear#chargeWear, and making them unbreakable is a config/`/give`
	// concern (maxDamage(0)), not a code path.
	//
	// No getSlot()/isDamageable() overrides: the ArmorItem.Type.HELMET constructor already fixes
	// the equipment slot, and re-declaring it only adds a method that can break on a rename.

	/** Item identifier, used by the config screen's "reset" tooltip and by the asset validator. */
	public static Identifier id() {
		return net.minecraft.registry.Registries.ITEM.getId(ModItems.HAZMAT_GOGGLES);
	}

	/** True if {@code entity} is wearing goggles; convenience wrapper so callers read naturally. */
	public static boolean worn(LivingEntity entity) {
		return HazmatGear.wearsGoggles(entity);
	}
}
