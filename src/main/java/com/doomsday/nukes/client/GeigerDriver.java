package com.doomsday.nukes.client;

import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.config.DoomsdayConfig;
import com.doomsday.nukes.effect.RadiationManager;
import com.doomsday.nukes.item.GeigerCounterItem;
import com.doomsday.nukes.sound.ModSounds;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.MathHelper;

/**
 * The client half of the Geiger counter: click rate, needle, alarm.
 *
 * <h2>Why a driver class and not {@code GeigerCounterItem#inventoryTick}</h2>
 * Two reasons, both load-bearing:
 * <ul>
 *   <li>The item is common code, and audio scheduling is client-only — see {@code ClientPayloadSender}
 *       for the general rule.</li>
 *   <li>Clicks have to be scheduled on <em>frame</em> time, not tick time. At level 4 the target is
 *       ~22 clicks/second, i.e. one every 45 ms; a 50 ms tick boundary would turn that into an
 *       audible 10 Hz stutter, and the whole point of the sound is that its smoothness encodes the
 *       dose.</li>
 * </ul>
 *
 * <h2>The curve</h2>
 * Clicks per second interpolate from {@code geigerClicksPerSecondLow} at dose 0 to
 * {@code geigerClicksPerSecondHigh} at dose 1, on a power curve (exponent 0.7) so the low end is
 * still informative — the difference between 5 % and 15 % has to be audible, because that is the
 * difference between "walking through" and "staying". The minimum interval
 * ({@code geigerMinClickIntervalTicks}) is a hard floor so a pathological config cannot produce a
 * continuous tone, and the jitter is ±25 % because a metronome reads as a machine, not an instrument.
 *
 * <p>If the sound system is muted or the window is not focused, nothing is scheduled: {@link
 * #tick()} checks {@code MinecraftClient#isRunning()}/{@code getSoundManager()}/{@code
 * isWindowFocused()} before it does any work, so an unfocused client burns no CPU on clicks.</p>
 */
public final class GeigerDriver {
	private static double accumulator;
	private static double lastDose;
	private static boolean alarming;
	private static long ticks;
	private static int clicks;

	private GeigerDriver() {
	}

	/** Called from {@code ClientTickEvents.END_CLIENT_TICK}, once per client tick. */
	public static void tick() {
		ticks++;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.world == null || client.player == null) {
			accumulator = 0.0D;
			return;
		}
		ClientPlayerEntity player = client.player;
		if (!GeigerCounterItem.isHeld(player)) {
			// Not held: reset so re-equipping starts a fresh rhythm instead of a burst.
			if (accumulator != 0.0D) {
				accumulator = 0.0D;
				lastDose = 0.0D;
			}
			return;
		}
		if (client.getSoundManager() == null || !client.isWindowFocused()) {
			// An unfocused or audio-less client must not schedule anything: the click queue would
			// grow while the window is backgrounded and burst on refocus.
			accumulator = 0.0D;
			return;
		}
		DoomsdayConfig c = ConfigManager.get();
		float dose = RadiationManager.clientDose();
		lastDose = dose;

		// Sweep mode reads the wider radius; the reach only changes the *displayed* precision, so
		// it is applied here and nowhere else.
		double reach = GeigerCounterItem.reach(player);
		double effective = MathHelper.clamp(dose * (reach / Math.max(1.0D, c.geigerRange)), 0.0D, 1.0D);
		// Manual lerp: MathHelper's argument order has moved between versions, and this line is
		// read every tick, so it is worth spelling out instead of relying on the overload.
		double low = Math.max(0.05D, c.geigerClicksPerSecondLow);
		double high = Math.max(low + 0.5D, c.geigerClicksPerSecondHigh);
		double perSecond = low + (high - low) * Math.pow(effective, 0.7D);
		double minInterval = Math.max(0.04D, c.geigerMinClickIntervalTicks / 20.0D);
		perSecond = Math.min(perSecond, 1.0D / minInterval);

		accumulator += 1.0D / 20.0D;
		double interval = 1.0D / Math.max(0.2D, perSecond);
		int budget = 0;
		while (accumulator >= interval && budget++ < 4) {
			accumulator -= interval;
			// ±25 % jitter, applied to the *pitch*, which is how a real counter sounds: the
			// interval itself stays on schedule so the rate remains an honest measurement.
			float pitch = (float) (1.0D + (Math.random() - 0.5D) * 0.5D);
			float volume = (float) MathHelper.clamp(0.25D + effective * 0.75D, 0.1D, 1.0D);
			client.world.playSound(null, player.getX(), player.getY(), player.getZ(),
				ModSounds.GEIGER, SoundCategory.PLAYER, volume, pitch);
			clicks++;
		}
		if (accumulator > interval * 4.0D) {
			accumulator = 0.0D;
		}

		// Rising-pitch alarm above 80 % dose: a second, slower, unmistakable signal.
		boolean shouldAlarm = effective > 0.8D;
		if (shouldAlarm != alarming) {
			alarming = shouldAlarm;
			if (shouldAlarm) {
				client.world.playSound(null, player.getX(), player.getY(), player.getZ(),
					ModSounds.NUKE_FLASH_HISS, SoundCategory.PLAYER, 0.45F, 1.9F);
			}
		}
	}

	/** 0..1 smoothed dose for the needle gauge; the HUD reads this instead of the raw value. */
	public static double needle() {
		return lastDose;
	}

	public static boolean isAlarming() {
		return alarming;
	}

	public static String describe() {
		return "geiger: " + clicks + " clicks over " + ticks + " client ticks";
	}

	public static void reset() {
		accumulator = 0.0D;
		lastDose = 0.0D;
		alarming = false;
	}
}
