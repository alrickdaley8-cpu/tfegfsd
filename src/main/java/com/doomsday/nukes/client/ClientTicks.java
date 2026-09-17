package com.doomsday.nukes.client;

/**
 * The client's own monotonic tick counter.
 *
 * <p>Why not {@code MinecraftClient#currentTick}? Because that field is the <em>game</em> tick and
 * it does not advance while a screen is open in singleplayer (the client still renders, the world
 * does not tick). Device countdowns and HUD fade animations are render-side, so they need a counter
 * that advances every frame batch regardless of pause state — otherwise opening the inventory in
 * singleplayer freezes the "T-12" banner at 12 while the timer keeps running.</p>
 *
 * <p>One class, one {@code long}, incremented from {@code ClientTickEvents.END_CLIENT_TICK}. It
 * exists so no other client class has to decide which clock to trust.</p>
 */
public final class ClientTicks {
	private static long ticks;

	private ClientTicks() {
	}

	public static void advance() {
		ticks++;
	}

	public static long get() {
		return ticks;
	}

	/** Seconds since launch, for animation phases that must not care about tick rate. */
	public static float seconds() {
		return ticks / 20.0F;
	}

	public static void reset() {
		ticks = 0L;
	}
}
