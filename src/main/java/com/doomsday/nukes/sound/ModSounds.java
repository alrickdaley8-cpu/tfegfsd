package com.doomsday.nukes.sound;

import com.doomsday.nukes.DoomsdayNukes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/**
 * The six registered sound events.
 *
 * <p>These are <em>identities</em>, not audio: the actual {@code .ogg} files live in
 * {@code assets/doomsday/sounds/} and are wired up by {@code assets/doomsday/sounds.json}. A
 * {@code SoundEvent} with no file behind it is legal and non-fatal — Minecraft logs
 * {@code Unable to load sound} and continues silently — which is what makes the placeholder
 * strategy safe: run {@code python3 tools/gen_sounds.py} to synthesise the sources locally, or
 * drop your own {@code .ogg} files in and nothing else has to change.</p>
 *
 * <h2>Category choices</h2>
 * Detonation sounds use {@link net.minecraft.sound.SoundCategory#RECORDS} rather than
 * {@code MASTER}/{@code BLOCKS} for two concrete reasons: the volume slider is a real control
 * surface (a server that only wants the boom can turn RECORDS down without losing footsteps),
 * and it is the only category vanilla lets travel beyond the 16-chunk
 * {@code SoundCategory.MASTER} clamp without being attached to an entity. Geiger clicks use
 * {@code PLAYER} so they follow the player's own volume and are not heard by nearby players
 * unless they are close (see {@code GeigerSoundPool}).
 */
public final class ModSounds {
	/** Rising two-tone civil-defiance siren played when a device arms, and looping while live. */
	public static final SoundEvent SIREN = register("siren");
	/** Geiger tick — short, dry, click-like; driven by {@code GeigerSoundPool}. */
	public static final SoundEvent GEIGER = register("geiger");
	/** Arming beep/relay chatter for stage 0. */
	public static final SoundEvent NUKE_ARM = register("nuke_arm");
	/** The high, thin hiss that arrives with the light, before any sound has time to travel. */
	public static final SoundEvent NUKE_FLASH_HISS = register("nuke_flash_hiss");
	/** The delayed far-field boom: played locally at a distance-derived time, never at the source. */
	public static final SoundEvent NUKE_DISTANT_BOOM_DELAYED = register("nuke_distant_boom_delayed");
	/** Long low rumble under the cloud, looped and pitched down. */
	public static final SoundEvent MUSHROOM_RUMBLE = register("mushroom_rumble");

	private ModSounds() {
	}

	private static SoundEvent register(String name) {
		Identifier id = DoomsdayNukes.id(name);
		return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
	}

	/** Called from the entrypoint; kept as a method so ordering is visible. */
	public static void register() {
		if (SIREN == null || MUSHROOM_RUMBLE == null) {
			throw new IllegalStateException("Sound events failed to register");
		}
	}

	/** Every registered event, for {@code /doomsday sounds} and the asset validator. */
	public static SoundEvent[] all() {
		return new SoundEvent[]{SIREN, GEIGER, NUKE_ARM, NUKE_FLASH_HISS,
			NUKE_DISTANT_BOOM_DELAYED, MUSHROOM_RUMBLE};
	}
}
