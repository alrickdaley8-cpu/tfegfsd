package com.doomsday.nukes.detonation;

/**
 * The seven coordinated phases of a detonation, plus {@link #STANDBY} for the
 * pre-arm state. Kept as an ordered enum so the network can carry a stage as a single
 * {@code varint} and so {@link DetonationTimeline} can assert monotonic ordering.
 *
 * <p>Stage 0 (arming) is intentionally <em>not</em> part of a {@code Detonation}
 * instance — arming belongs to the device (BlockEntity) and can be cancelled, while a
 * detonation, once created, is an immutable timeline that always runs to completion.</p>
 */
public enum DetonationStage {
	/** Device exists, not armed. */
	STANDBY(0, "standby"),
	/** Device armed, countdown running (blinking light, mob panic, siren). */
	ARMING(1, "arming"),
	/** Stage 1 — thermal/EM pulse: whiteout, desaturation, blindness, light. */
	FLASH(2, "flash"),
	/** Stage 2 — fireball growth + terrain transmutation inside it. */
	FIREBALL(3, "fireball"),
	/** Stage 3 — destructive wave + visible condensation dome + camera shake. */
	SHOCKWAVE(4, "shockwave"),
	/** Stage 4 — stem/cap/skirt cloud, the long-lived centrepiece. */
	MUSHROOM_CLOUD(5, "mushroom_cloud"),
	/** Stage 5 — ash rain, contamination, atmosphere darkening, EMP tail. */
	FALLOUT(6, "fallout"),
	/** Aftermath — sky/fog returning to normal, radiation lingering. */
	AFTERMATH(7, "aftermath"),
	/** Terminal. Client visuals released, server record dropped. */
	COMPLETE(8, "complete");

	private final int index;
	private final String key;

	DetonationStage(int index, String key) {
		this.index = index;
		this.key = key;
	}

	public int index() {
		return index;
	}

	/** Translation key suffix, e.g. {@code subtitle.doomsday.stage.flash}. */
	public String key() {
		return key;
	}

	public boolean atLeast(DetonationStage other) {
		return index >= other.index;
	}

	/**
	 * Lookup by the config/translation key, used by {@code /doomsday preview <stage>} and by the
	 * stage-change packet. Returns null on an unknown key so the caller can report it instead of
	 * silently playing a different stage — a typo in a debug command should be visible.
	 */
	public static DetonationStage byKey(String key) {
		if (key == null) {
			return null;
		}
		for (DetonationStage s : values()) {
			if (s.key.equalsIgnoreCase(key.trim())) {
				return s;
			}
		}
		return null;
	}

	public static DetonationStage byIndex(int index) {
		DetonationStage[] all = values();
		return index >= 0 && index < all.length ? all[index] : COMPLETE;
	}
}
