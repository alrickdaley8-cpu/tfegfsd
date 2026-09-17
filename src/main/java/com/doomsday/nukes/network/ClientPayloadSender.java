package com.doomsday.nukes.network;

import net.minecraft.network.packet.CustomPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one seam that lets <b>common</b> code send a client-originated packet without importing a
 * client class.
 *
 * <h2>Why this exists</h2>
 * A dedicated server must never touch {@code net.minecraft.client.*}. Yet a few common classes
 * genuinely need a C2S send from their client half — {@code RemoteDetonatorItem#use} runs on both
 * sides, and the client side of it has to tell the server to fire the linked device. Calling
 * {@code ClientPlayNetworking} from that method compiles, then dies with
 * {@code NoClassDefFoundError} the first time a player right-clicks on a server that has no jar,
 * and Fabric's environment checker rejects it outright in a development launch.
 *
 * <p>So the common code declares a plug; the client entrypoint fills it in:</p>
 * <pre>{@code
 * // DoomsdayNukesClient.onInitializeClient()
 * ClientPayloadSender.install(ClientPlayNetworking::send);
 * }</pre>
 *
 * <p>On a dedicated server the plug stays unset, {@link #send} becomes a no-op — which is the
 * correct semantics anyway, since there is no local player to act there — and a client that failed
 * to install logs once instead of crashing. Nothing here imports a client type, so the class is
 * safe to load anywhere.</p>
 */
public final class ClientPayloadSender {
	private static final Logger LOGGER = LoggerFactory.getLogger("DoomsdayNukes|net");

	/** Transport the client entrypoint installs. */
	@FunctionalInterface
	public interface Transport {
		void send(CustomPayload payload);
	}

	private static volatile Transport transport;
	private static boolean warnedMissing;

	private ClientPayloadSender() {
	}

	/** Called exactly once, from {@code DoomsdayNukesClient}. */
	public static void install(Transport next) {
		transport = next;
	}

	public static boolean installed() {
		return transport != null;
	}

	/** Fire-and-forget. Never throws: a dropped informational packet is not worth a crash. */
	public static void send(CustomPayload payload) {
		if (payload == null) {
			return;
		}
		Transport current = transport;
		if (current == null) {
			if (!warnedMissing) {
				warnedMissing = true;
				LOGGER.info("No client payload transport installed; C2S packets from common code "
					+ "are ignored (expected on a dedicated server).");
			}
			return;
		}
		try {
			current.send(payload);
		} catch (RuntimeException e) {
			LOGGER.warn("Failed to send {}: {}", payload.getClass().getSimpleName(), e.toString());
		}
	}
}
