package com.doomsday.nukes.item;

import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.effect.RadiationManager;
import com.doomsday.nukes.util.DText;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.stat.Stats;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;

/**
 * Iodine tablets: an *exposure deferral*, not a cure.
 *
 * <h2>Exactly what happens</h2>
 * <ul>
 *   <li>On the server the player's iodine window opens for {@code iodineProtectionSeconds}
 *       (default 90 s). While it is open, {@link RadiationManager#iodineMultiplier} scales the
 *       incoming dose by {@code 1 - iodineAmplifierReduction / 4} — with the default amplifier
 *       reduction of 2 that is a 50 % cut, which is what "taking your pills" should feel like:
 *       meaningful, temporary, not immunity.</li>
 *   <li>Existing exposure is untouched. Iodine blocks potassium-iodide from *preventing* uptake of
 *       fresh radioiodine; it does nothing to damage already done, and the mod's model follows the
 *       real one rather than becoming a health potion.</li>
 *   <li>Cooldown: one tablet per {@code iodineCooldownSeconds} window, enforced with the vanilla
 *       item-cooldown manager so the client greys the out-of-range hotbar slot automatically.</li>
 * </ul>
 *
 * <p>Stack size is 16 and the item is food-like (it uses the vanilla eat sound and the standard\n * {@code finishUsing} path is not needed — tablets are instant). Making it a real {@code Item} with an
 * instant {@code use} rather than {@code edible()} keeps the interaction to one packet instead of
 * a 32-tick eating animation the player has to wait through while running from fallout.</p>
 */
public class IodineTabletItem extends Item {
	/** Vanilla-equivalent cooldown for "don't pop these back to back", in seconds. */
	public static final double COOLDOWN_SECONDS = 8.0D;

	public IodineTabletItem(Settings settings) {
		super(settings);
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		if (user.getItemCooldownManager().isCoolingDown(this)) {
			// PASS, not FAIL: a cooling-down tablet must not eat the click, so the player can still
			// use the item in their other hand / place the stack.
			return TypedActionResult.pass(stack);
		}
		int cooldown = Math.max(1, (int) Math.round(COOLDOWN_SECONDS * 20.0D));
		user.getItemCooldownManager().set(this, cooldown);

		if (world.isClient) {
			// Cosmetic + predictive: the swing and the sound are the feedback, and the action-bar
			// line arrives from the server on the next tick anyway.
			user.swingHand(hand);
			return TypedActionResult.success(stack);
		}
		if (!(user instanceof ServerPlayerEntity player)) {
			return TypedActionResult.pass(stack);
		}
		if (!ConfigManager.get().radiationEnabled) {
			player.sendMessage(DText.of("gui.doomsday.iodine.disabled"), true);
			return TypedActionResult.fail(stack);
		}
		double seconds = ConfigManager.get().iodineProtectionSeconds;
		RadiationManager.takeIodine(player, seconds);
		player.incrementStat(Stats.USED.getOrCreateStat(this));
		if (!player.getAbilities().allowModifyWorld) {
			stack.decrement(1);
		}
		world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_GENERIC_EAT,
			SoundCategory.PLAYERS, 0.9F, 1.35F);
		player.sendMessage(DText.of("gui.doomsday.iodine.taken",
			(int) Math.round(seconds)), true);
		return TypedActionResult.success(stack);
	}
}
