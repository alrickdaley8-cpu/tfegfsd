package com.doomsday.nukes.client;

import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The client's mirror of every device it has been told about.
 *
 * <h2>Why a mirror at all</h2>
 * {@code DeviceStateS2CPacket} arrives whenever a device is armed, disarmed or reloaded — not once
 * a tick. The HUD and the control screen still need "is the thing I'm standing on counting down,
 * and how long", and the answer has to advance every frame. So the packet writes a snapshot here and
 * the countdown is <em>extrapolated</em> locally from {@code ticksRemaining} by the client's own
 * tick counter, with the next packet correcting any drift.
 *
 * <p>That is the entire protocol: no per-tick device traffic, and a client that misses a packet
 * self-corrects within one second because the server re-broadcasts state on the device's own 1 Hz
 * beat (see {@code NukeBlockEntity#tickBroadcast}).</p>
 *
 * <h2>Staleness</h2>
 * Entries expire after {@link #STALE_TICKS} client ticks without a refresh. Without that, a device
 * that was destroyed while out of tracking range would keep a fake countdown on someone's HUD
 * forever, which is exactly the kind of lie an instrument must not tell.
 */
public final class ClientDeviceStates {
	/** Drop an entry that has not been refreshed for this many client ticks (30 s). */
	public static final int STALE_TICKS = 600;

	/** One mirrored device. Immutable; replaced wholesale on each packet. */
	public record State(BlockPos pos, int presetOrdinal, boolean armed, int ticksRemaining,
						double yieldKt, boolean empSuppressed, long writtenAtTick) {
		/**
		 * Ticks left as of <em>now</em>, extrapolated from the snapshot. Never negative, never
		 * above what was sent, so a paused or re-sent state cannot make the number climb.
		 */
		public int extrapolated() {
			if (!armed) {
				return 0;
			}
			int elapsed = (int) Math.max(0L, (ClientTicks.get() - writtenAtTick));
			return Math.max(0, Math.min(ticksRemaining, ticksRemaining - elapsed));
		}

		public int secondsRemaining() {
			return (extrapolated() + 19) / 20;
		}
	}

	private static final Map<BlockPos, State> STATES = new ConcurrentHashMap<>();

	private ClientDeviceStates() {
	}

	public static void put(State state) {
		if (state != null) {
			STATES.put(state.pos(), state);
		}
	}

	/** Called by the packet receiver when the server reports a device is no longer armed. */
	public static void remove(BlockPos pos) {
		if (pos != null) {
			STATES.remove(pos);
		}
	}

	/** Nearest mirrored device within {@code radius} blocks of {@code centre}, or null. */
	public static State nearest(net.minecraft.util.math.Vec3d centre, double radius) {
		State best = null;
		double bestDistance = radius * radius;
		for (State s : STATES.values()) {
			double d = s.pos().toCenterPos().squaredDistanceTo(centre);
			if (d <= bestDistance) {
				bestDistance = d;
				best = s;
			}
		}
		return best;
	}

	/** Any <em>armed</em> device near the player — the HUD banner condition. */
	public static State nearestArmed(net.minecraft.util.math.Vec3d centre, double radius) {
		State best = null;
		double bestDistance = radius * radius;
		for (State s : STATES.values()) {
			if (!s.armed() || s.extrapolated() <= 0) {
				continue;
			}
			double d = s.pos().toCenterPos().squaredDistanceTo(centre);
			if (d <= bestDistance) {
				bestDistance = d;
				best = s;
			}
		}
		return best;
	}

	public static int size() {
		return STATES.size();
	}

	/** Prune expired entries; called from the client tick, cheap because the map is tiny. */
	public static void sweep() {
		long now = ClientTicks.get();
		STATES.entrySet().removeIf(e -> now - e.getValue().writtenAtTick() > STALE_TICKS);
	}

	public static void clear() {
		STATES.clear();
	}

}
