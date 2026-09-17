package com.doomsday.nukes.item;

import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.effect.RadiationManager;
import com.doomsday.nukes.util.DText;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * The Geiger counter: an instrument, not a weapon.
 *
 * <h2>Division of labour</h2>
 * This class holds <b>only</b> common-side state. All audio, the needle gauge and the click rate
 * live in {@code com.doomsday.nukes.client.GeigerDriver}, registered from the client entrypoint.
 * The reason is Rule 4: if an item class in the common source set referenced a client class, a
 * dedicated server running without the Minecraft client jar would throw
 * {@code NoClassDefFoundError} the first time anyone held one.
 *
 * <h2>What the server does</h2>
 * <ul>
 *   <li>Tracks which players are holding a counter, so the dose field around them is synced
 *       (and the HUD needle has numbers to point at). Holding the device is what buys you the
 *       readout — that is the design.</li>
 *   <li>{@link #use} toggles <em>sweep mode</em>: a wider sampling radius and an audible ping per
 *       activation, at the cost of double dose display lag. Purely informational, no gameplay
 *       effect, so it can never be abused for damage.</li>
 * </ul>
 *
 * <p>{@link #inventoryTick} is intentionally cheap: one boolean comparison against the held slot
 * and an integer counter. No world queries, no entity scans — the scanning happens once per
 * player per second inside {@code RadiationManager}.</p>
 */
public class GeigerCounterItem extends Item {
	/** NBT key: sweep mode on/off. */
	public static final String NBT_SWEEP = "SweepMode";
	/** NBT key: last dose level the wearer was warned about, for the rising-pitch alarm. */
	public static final String NBT_LAST_LEVEL = "LastLevel";

	public GeigerCounterItem(Settings settings) {
		super(settings);
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		if (world.isClient) {
			// Predict the toggle so the needle and the ping are immediate; the server confirms
			// within a tick and the value is authoritative for anything that matters.
			boolean sweep = !stack.getOrCreateNbt().getBoolean(NBT_SWEEP);
			stack.getOrCreateNbt().putBoolean(NBT_SWEEP, sweep);
			// Local playback for the holder only: playSound on the entity, not the world, so the
			// ping is not double-sent by the server's own broadcast.
			user.playSound(com.doomsday.nukes.sound.ModSounds.GEIGER, 0.65F, sweep ? 1.9F : 1.35F);
			return TypedActionResult.success(stack);
		}
		if (user instanceof ServerPlayerEntity sp) {
			boolean sweep = !stack.getOrCreateNbt().getBoolean(NBT_SWEEP);
			stack.getOrCreateNbt().putBoolean(NBT_SWEEP, sweep);
			RadiationManager.setSweep(sp, sweep);
			sp.sendMessage(DText.of(sweep
				? "gui.doomsday.geiger.sweep_on" : "gui.doomsday.geiger.sweep_off"), true);
		}
		return TypedActionResult.success(stack);
	}

	@Override
	public void inventoryTick(ItemStack stack, World world, Entity entity, int slot,
							  boolean selected) {
		if (world.isClient || !(entity instanceof ServerPlayerEntity player)) {
			return;
		}
		// Only the held counter matters; an item in a chest must not keep a player "scanning".
		boolean held = selected && (player.getMainHandStack() == stack
			|| player.getOffHandStack() == stack);
		RadiationManager.setHoldingCounter(player, held);
	}

	/** Detection reach in blocks, doubled while sweeping. */
	public static double reach(LivingEntity user) {
		double base = ConfigManager.get().geigerRange;
		return isSweeping(user) ? base * 2.0D : base;
	}

	public static boolean isSweeping(LivingEntity user) {
		if (user == null) {
			return false;
		}
		ItemStack main = user.getMainHandStack();
		if (main.getItem() instanceof GeigerCounterItem) {
			return main.getOrCreateNbt().getBoolean(NBT_SWEEP);
		}
		ItemStack off = user.getOffHandStack();
		if (off.getItem() instanceof GeigerCounterItem) {
			return off.getOrCreateNbt().getBoolean(NBT_SWEEP);
		}
		return false;
	}

	/** Common-safe: used by the client driver to decide whether to run at all. */
	public static boolean isHeld(LivingEntity user) {
		if (user == null) {
			return false;
		}
		return user.getMainHandStack().getItem() instanceof GeigerCounterItem
			|| user.getOffHandStack().getItem() instanceof GeigerCounterItem;
	}

	/**
	 * Dose at the entity's position, in 0..1, chunk-quantised. Exposed as a static so the client
	 * driver and the server HUD sync share one definition.
	 */
	public static float doseAt(World world, Vec3d pos) {
		return RadiationManager.doseAt(world, pos);
	}
}
