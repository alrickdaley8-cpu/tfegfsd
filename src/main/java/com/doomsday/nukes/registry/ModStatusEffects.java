package com.doomsday.nukes.registry;

import com.doomsday.nukes.DoomsdayNukes;
import com.doomsday.nukes.effect.RadiationSicknessEffect;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

/**
 * Custom status effects.
 *
 * <p>Only one effect is registered. Fatigue-like symptoms are delivered by applying the
 * <em>vanilla</em> {@code WEAKNESS} and {@code MINING_FATIGUE} instances alongside it (config
 * {@code radiationAppliesVanillaDebuffs}) rather than by registering custom attributes. That is
 * a deliberate correctness choice: a custom {@code EntityAttribute} requires a registry
 * <em>key</em> that every other mod must agree with, an attribute-serializer registration, and
 * an {@code AttributesMixin}-adjacent contract on a dedicated server — none of which buys a
 * single pixel or hit point here, and all of which are classic sources of multiplayer
 * desync.</p>
 */
public final class ModStatusEffects {
	/** Radiation Sickness — dose-proportional, progressive, curable by iodine. */
	public static final StatusEffect RADIATION = register("radiation",
		new RadiationSicknessEffect());

	private ModStatusEffects() {
	}

	private static StatusEffect register(String name, StatusEffect effect) {
		return Registry.register(Registries.STATUS_EFFECT, DoomsdayNukes.id(name), effect);
	}

	public static void register() {
		if (RADIATION == null) {
			throw new IllegalStateException("Radiation status effect failed to register");
		}
	}
}
