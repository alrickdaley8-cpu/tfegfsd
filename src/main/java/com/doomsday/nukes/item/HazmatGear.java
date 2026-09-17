package com.doomsday.nukes.item;

import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.effect.RadiationManager;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;

/**
 * The single authority on "is this player protected, and by how much".
 *
 * <p>Both protection systems (flash blindness and radiation) and both the Geiger HUD indicator
 * and the config screen ask this class, so there is exactly one definition of what wearing the
 * goggles means. That matters more than it sounds: the common failure mode for protective
 * equipment in Minecraft mods is the effect system checking the slot while the damage system
 * checks the item, and the two disagreeing the moment another mod changes equipment order.</p>
 *
 * <h2>Protection is a multiplier, never an absolute</h2>
 * Spec: goggles give 80 % radiation resistance. That is expressed as
 * {@code goggleRadiationMultiplier = 0.2} on the incoming dose, so standing in a hot zone with
 * goggles on still hurts — slowly. The only way to reach absolute immunity is for a server admin
 * to set the multiplier to 0, which is a configuration decision, not a code path.</p>
 */
public final class HazmatGear {
	private HazmatGear() {
	}

	/** True when the entity's head slot holds Hazmat Goggles (or a renamed copy of them). */
	public static boolean wearsGoggles(LivingEntity entity) {
		if (entity == null) {
			return false;
		}
		ItemStack head = entity.getEquippedStack(EquipmentSlot.HEAD);
		return !head.isEmpty() && head.getItem() instanceof HazmatGogglesItem;
	}

	/**
	 * Radiation dose multiplier for this entity: 1.0 unprotected, config-scaled when the goggles
	 * are on, and further reduced by an active iodine tablet.
	 */
	public static double radiationMultiplier(LivingEntity entity) {
		if (entity == null) {
			return 1.0D;
		}
		double mult = 1.0D;
		if (wearsGoggles(entity)) {
			mult *= Math.max(0.0D, ConfigManager.get().goggleRadiationMultiplier);
		}
		if (entity instanceof PlayerEntity player) {
			double iodine = RadiationManager.iodineMultiplier(player);
			mult *= Math.max(0.0D, Math.min(1.0D, iodine));
		}
		return mult;
	}

	/**
	 * Flash-blindness reduction. Goggles do not make you immune to staring at a nuclear fireball;
	 * they make it survivable. The 0.35 factor lives in {@code Detonation.applyFlash} so the
	 * attenuation curve and the equipment rule are decided in the same place.
	 */
	public static boolean reducesFlash(LivingEntity entity) {
		return wearsGoggles(entity);
	}

	/** HUD: is the protection currently doing anything for this player? */
	public static boolean indicatorActive(LivingEntity entity) {
		return wearsGoggles(entity);
	}

	/** Durability cost of a detonation's thermal pulse, applied once per player per event. */
	public static void chargeWear(PlayerEntity player, double intensity) {
		if (player == null || intensity <= 0.05D) {
			return;
		}
		ItemStack head = player.getEquippedStack(EquipmentSlot.HEAD);
		if (head.getItem() instanceof HazmatGogglesItem goggles && !head.isEmpty()
			&& head.getMaxDamage() > 0) {
			int damage = (int) Math.max(1L, Math.round(intensity * 6.0D));
			int next = head.getDamage() + damage;
			if (next >= head.getMaxDamage()) {
				// Explicit break: the thermal pulse should be able to cost you the goggles, and
				// using setDamage/equipStack avoids depending on any one ItemStack damage
				// overload, which is one of the fastest-churning APIs in this range of versions.
				player.equipStack(EquipmentSlot.HEAD, ItemStack.EMPTY);
				player.sendMessage(goggles.brokenMessage(), true);
			} else {
				head.setDamage(next);
			}
		}
	}
}
