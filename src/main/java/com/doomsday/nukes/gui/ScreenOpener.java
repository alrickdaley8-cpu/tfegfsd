package com.doomsday.nukes.gui;

import com.doomsday.nukes.detonation.NukePreset;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The seam between common interaction code and the client GUI.
 *
 * <h2>The problem</h2>
 * {@code NukeBlock#onUse} runs on both sides, and the client half has to open the control screen.
 * Writing {@code MinecraftClient.getInstance().setScreen(new NukeControlScreen(...))} there is the
 * obvious thing, and it is wrong twice over: a dedicated server has no {@code MinecraftClient} to
 * link against (crash on first use, or a hard rejection by Fabric's environment checker in dev), and
 * the block class then cannot be loaded at all without the client jar present.
 *
 * <h2>The pattern</h2>
 * Common code declares an interface and a slot; the <em>client entrypoint</em> installs an
 * implementation at startup:
 * <pre>{@code
 * // DoomsdayNukesClient.onInitializeClient()
 * ScreenOpener.install(new ScreenOpener.Handler() {
 *     public void openControlScreen(BlockPos pos, NukePreset preset, boolean armed, int ticks) {
 *         MinecraftClient c = MinecraftClient.getInstance();
 *         c.setScreen(new NukeControlScreen(pos, preset, armed, ticks));
 *     }
 *     ...
 * });
 * }</pre>
 * On a server the slot stays empty, {@link #openControlScreen} is a no-op with one log line, and
 * nothing references a client class from a common class file. Same trick as
 * {@code ClientPayloadSender}; both exist so the "works on singleplayer, bricks a server" bug has
 * no way to happen by accident.
 */
public final class ScreenOpener {
	private static final Logger LOGGER = LoggerFactory.getLogger("DoomsdayNukes|gui");

	/** Implemented by the client. */
	public interface Handler {
		void openControlScreen(BlockPos pos, NukePreset preset, boolean armed, int ticksRemaining);

		void openConfigScreen();
	}

	private static volatile Handler handler;
	private static boolean warnedMissing;

	private ScreenOpener() {
	}

	public static void install(Handler next) {
		handler = next;
	}

	public static boolean available() {
		return handler != null;
	}

	public static void openControlScreen(BlockPos pos, NukePreset preset, boolean armed,
										 int ticksRemaining) {
		Handler current = handler;
		if (current == null) {
			missing();
			return;
		}
		try {
			current.openControlScreen(pos, preset, armed, ticksRemaining);
		} catch (RuntimeException e) {
			// A GUI failure must never roll back the interaction that caused it: the device state
			// is server-side and already correct.
			LOGGER.warn("Could not open the control screen for {} ({}).", pos.toShortString(),
				preset.name(), e);
		}
	}

	public static void openConfigScreen() {
		Handler current = handler;
		if (current == null) {
			missing();
			return;
		}
		current.openConfigScreen();
	}

	/** Line shown in chat when the GUI is unavailable (e.g. a server without the client class). */
	public static Text unavailableMessage() {
		return Text.translatable("gui.doomsday.screen.unavailable");
	}

	private static void missing() {
		if (!warnedMissing) {
			warnedMissing = true;
			LOGGER.info("No ScreenOpener installed; GUI requests are ignored. Expected on a "
				+ "dedicated server — the client entrypoint installs it in onInitializeClient().");
		}
	}
}
