package com.doomsday.nukes.util;

import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * One place to build translatable text, so every user-visible string in the mod is a key that
 * exists in {@code assets/doomsday/lang/*.json} — which {@code tools/AssetValidator.java} then
 * enforces at build time.
 *
 * <p>Numbers passed as arguments are formatted with {@link java.util.Locale#ROOT} inside the
 * caller, never with a locale-dependent {@code %s}, because these strings are also compared by
 * the asset validator and must not vary with the client's locale settings.</p>
 */
public final class DText {
	private DText() {
	}

	/** {@code key} with format arguments, as a translatable component. */
	public static Text of(String key, Object... args) {
		return args == null || args.length == 0
			? Text.translatable(key)
			: Text.translatable(key, args);
	}

	public static MutableText mutable(String key, Object... args) {
		return of(key, args).copy();
	}

	public static Text of(String key, Formatting color, Object... args) {
		return of(key, args).copy().formatted(color);
	}

	/** Only for strings that are genuinely not translatable (block position dumps and the like). */
	public static Text raw(String value) {
		return Text.literal(value);
	}

	/** The stage-0 banner: a live, ticking device. */
	public static Text armedBanner(int seconds) {
		return of("gui.doomsday.device.armed_banner", seconds).copy()
			.formatted(Formatting.RED, Formatting.BOLD);
	}

	public static Text stageName(com.doomsday.nukes.detonation.DetonationStage stage) {
		return of("gui.doomsday.stage." + stage.key());
	}

	public static Text deviceName(com.doomsday.nukes.detonation.NukePreset preset) {
		return of("block.doomsday." + preset.blockId());
	}
}
