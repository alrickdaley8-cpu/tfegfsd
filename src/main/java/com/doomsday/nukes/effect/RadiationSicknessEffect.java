package com.doomsday.nukes.effect;

import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.config.DoomsdayConfig;
import com.doomsday.nukes.item.HazmatGear;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;

/**
 * Radiation Sickness — the accumulating consequence of a detonation.
 *
 * <h2>Design: a dosimeter, not a damage-per-second tick</h2>
 * <ul>
 *   <li>The <b>effect</b> is purely a modifier stack: it slows mining, weakens attacks, drains
 *       hunger a little faster, and applies damage on a coarse interval (
 *       {@code radiationTickIntervalSeconds}, default 4 s) so that radiation reads as an
 *       <em>attrition</em> system rather than a lava mechanic.</li>
 *   <li>The <b>number</b> (0..100 exposure) lives in {@link RadiationManager}, server-side, and is
 *       what the HUD, the Geiger counter and the scoreboard read. An effect can be removed by milk
 *       or a command; exposure is the truth and decays on its own.</li>
 *   <li>Amplifier 0..{@code radiationMaxAmplifier} is derived from exposure in
 *       {@link RadiationManager}; this class only ever <em>applies</em> what it is told, which keeps
 *       the effect stateless and therefore safe to save/load with the player.</li>
 * </ul>
 *
 * <p>The {@code -4 s} damage cadence is also the reason {@link #canApplyUpdateEffect} gates on
 * {@code duration % interval == 0} instead of returning true: {@code applyUpdateEffect} would
 * otherwise run 20× a second per affected player.</p>
 */
public class RadiationSicknessEffect extends StatusEffect {
	/** Yarn-green, the same family as the contamination HUD tint. */
	public static final int COLOR = 0x8FBF3F;

	public RadiationSicknessEffect() {
		super(StatusEffectCategory.HARMFUL, COLOR);
	}

	@Override
	public void applyUpdateEffect(LivingEntity entity, int amplifier) {
		DoomsdayConfig c = ConfigManager.get();
		if (!c.radiationEnabled) {
			return;
		}
		if (entity instanceof PlayerEntity player) {
			if (player.isCreative() || player.isSpectator()) {
				// Creative players are exempt by design: radiation is a survival pressure, and
				// punishing a builder for walking through a crater is not a mechanic, it is noise.
				return;
			}
			// Damage on the configured coarse interval only.
			player.damage(player.getDamageSources().magic(
				c.radiationDamagePerTick * (amplifier + 1)));
			// Hunger drains a little faster the sicker you are.
			player.getHungerManager().addExhaustion(0.2F + 0.15F * amplifier);
		} else {
			// Mobs/animals get the damage but not the hunger bookkeeping.
			entity.damage(entity.getDamageSources().magic(
				c.radiationDamagePerTick * (amplifier + 1)));
		}

		if (c.radiationAppliesVanillaDebuffs) {
			// Re-applied each interval, always with a short tail so they lapse between beats —
			// this is what makes the debuffs track the exposure curve instead of outliving it.
			int tail = intervalTicks(c) + 20;
			apply(entity, StatusEffects.WEAKNESS, amplifier, tail);
			apply(entity, StatusEffects.MINING_FATIGUE, amplifier, tail);
			if (amplifier >= 2) {
				// Nausea from amplifier 2 up: at that point you are dying, not inconvenienced.
				apply(entity, StatusEffects.NAUSEA, 0, tail);
			}
			if (amplifier >= 3) {
				apply(entity, StatusEffects.BLINDNESS, 0, Math.max(20, tail / 2));
			}
		}
	}

	private static void apply(LivingEntity entity, net.minecraft.registry.entry.RegistryEntry<StatusEffect> type,
							  int amplifier, int duration) {
		StatusEffectInstance existing = entity.getStatusEffect(type);
		if (existing == null || existing.getAmplifier() < amplifier || existing.getDuration() < duration) {
			entity.addStatusEffect(new StatusEffectInstance(type, duration, amplifier, false, true));
		}
	}

	/** Ticks between {@link #applyUpdateEffect} beats, from {@code radiationTickIntervalSeconds}. */
	public static int intervalTicks(DoomsdayConfig c) {
		return Math.max(20, DoomsdayConfig.ticks(c.radiationTickIntervalSeconds));
	}

	@Override
	public boolean canApplyUpdateEffect(int duration, int amplifier) {
		if (duration <= 0) {
			return false;
		}
		int interval = intervalTicks(ConfigManager.get());
		// The final beat matters more than the regular ones: it is the last damage before the
		// effect expires, and without it a 4 s interval would silently skip it.
		return duration % interval == 0 || duration <= 2;
	}

	/**
	 * Radiation is one of the few things in this mod that is <em>not</em> fully cancelled by
	 * protective gear: goggles cut the dose, they do not make you immune, so a long enough stay in
	 * a hot zone still kills you. This method exists so the HUD can state that difference plainly.
	 */
	public static boolean partiallyBlockedBy(LivingEntity entity) {
		return entity instanceof net.minecraft.entity.player.PlayerEntity player
			&& HazmatGear.protectionFactor(player) < 1.0D;
	}

}
