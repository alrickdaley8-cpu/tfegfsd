package com.doomsday.nukes.compat;

import com.doomsday.nukes.DoomsdayNukes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Empirically-sourced support for suspending beacons during an EMP without depending on
 * BeaconLib, a datapack hack, or a mixin into beacon logic.
 *
 * <h2>Why reflection</h2>
 * A beacon's active power level is the field that both {@code BeaconBlockEntity}'s tick and
 * the beacon renderer read. Writing it is the only change that (a) genuinely disables the
 * beacon, (b) is perfectly reversible, and (c) does not alter redstone or world state that
 * the player could notice being "restored" incorrectly. There is no public API for it, and a
 * mixin into vanilla beacon logic to add one field write would be exactly the kind of
 * unnecessary, conflict-prone injection this mod forbids itself. So: one narrow, cached,
 * fail-soft reflective write — and if the field is not there (Sodium-era refactors, a
 * different mappings channel, a future MC version), the EMP simply doesn't touch beacons and
 * says so once in the log.
 *
 * <p>Field/method probes are resolved once, on first use, on the server thread.</p>
 */
public final class BeaconEmpSupport {
	/** Sentinel: this Minecraft build does not expose what we need. */
	public static final int UNSUPPORTED = Integer.MIN_VALUE;

	private static volatile boolean probed;
	private static Field levelField;
	private static Method markDirtyMethod;

	private BeaconEmpSupport() {
	}

	private static void probe(Class<?> type) {
		if (probed) {
			return;
		}
		synchronized (BeaconEmpSupport.class) {
			if (probed) {
				return;
			}
			for (String name : new String[]{"level", "power", "beaconLevel", "intensity"}) {
				try {
					Field f = type.getDeclaredField(name);
					f.setAccessible(true);
					if (f.getType() == int.class) {
						levelField = f;
						break;
					}
				} catch (ReflectiveOperationException ignored) {
					// try the next candidate name
				}
			}
			try {
				markDirtyMethod = type.getMethod("markDirty");
			} catch (ReflectiveOperationException ignored) {
				markDirtyMethod = null;
			}
			probed = true;
			if (levelField == null) {
				DoomsdayNukes.LOGGER.info(
					"Beacon EMP suppression unavailable in this Minecraft build (no int level field on "
						+ "BeaconBlockEntity); the EMP will still affect lamps, torches and devices.");
			}
		}
	}

	/**
	 * Captures and zeroes a beacon's level.
	 *
	 * @return the previous level (0 or more) so it can be restored, or {@link #UNSUPPORTED}
	 *         when the block is not a beacon or the field is unavailable
	 */
	public static int captureAndSuspend(ServerWorld world, BlockPos pos) {
		net.minecraft.block.entity.BlockEntity be = world.getBlockEntity(pos);
		if (be == null) {
			return UNSUPPORTED;
		}
		probe(be.getClass());
		if (levelField == null) {
			return UNSUPPORTED;
		}
		try {
			int before = levelField.getInt(be);
			levelField.setInt(be, 0);
			if (markDirtyMethod != null) {
				markDirtyMethod.invoke(be);
			}
			// A level of 0 makes the beacon skip both effect application and beam construction,
			// which is exactly "temporarily disabled".
			return Math.max(0, before);
		} catch (ReflectiveOperationException | RuntimeException e) {
			DoomsdayNukes.LOGGER.debug("Beacon EMP suspend failed at {}", pos, e);
			return UNSUPPORTED;
		}
	}

	/** Writes the captured level back; safe to call even when the beacon moved away. */
	public static void restore(ServerWorld world, BlockPos pos, int level) {
		net.minecraft.block.entity.BlockEntity be = world.getBlockEntity(pos);
		if (be == null) {
			return;
		}
		probe(be.getClass());
		if (levelField == null) {
			return;
		}
		try {
			if (levelField.getInt(be) != level) {
				levelField.setInt(be, level);
			}
			if (markDirtyMethod != null) {
				markDirtyMethod.invoke(be);
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			DoomsdayNukes.LOGGER.debug("Beacon EMP restore failed at {}", pos, e);
		}
	}

	/** True when this build allows beacon suspension — used by the status command. */
	public static boolean available() {
		if (!probed) {
			try {
				probe(Class.forName("net.minecraft.block.entity.BeaconBlockEntity"));
			} catch (ClassNotFoundException e) {
				probed = true;
				return false;
			}
		}
		return levelField != null;
	}
}
