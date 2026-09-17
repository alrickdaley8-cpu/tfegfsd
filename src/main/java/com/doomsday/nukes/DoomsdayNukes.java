package com.doomsday.nukes;

import com.doomsday.nukes.command.DoomsdayCommands;
import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.detonation.DetonationManager;
import com.doomsday.nukes.effect.RadiationManager;
import com.doomsday.nukes.network.ModPackets;
import com.doomsday.nukes.registry.ModBlockEntities;
import com.doomsday.nukes.registry.ModBlocks;
import com.doomsday.nukes.registry.ModEntities;
import com.doomsday.nukes.registry.ModItemGroups;
import com.doomsday.nukes.registry.ModItems;
import com.doomsday.nukes.registry.ModStatusEffects;
import com.doomsday.nukes.sound.ModSounds;
import com.doomsday.nukes.world.EMPManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common (logical-server-safe) entrypoint.
 *
 * <p>Design contract enforced here:</p>
 * <ul>
 *   <li><b>No client class is referenced from this file.</b> Every render/shader/HUD
 *       type lives under {@code com.doomsday.nukes.client} behind
 *       {@code @Environment(EnvType.CLIENT)} and is only touched from
 *       {@code DoomsdayNukesClient}. A dedicated server therefore never loads a
 *       Minecraft client class.</li>
 *   <li><b>One</b> {@code END_SERVER_TICK} registration, with three bounded steps: the
 *       {@link DetonationManager} stage driver, {@link RadiationManager}'s per-player
 *       integration, and {@link EMPManager}'s zone sweep. Each is O(active things) with a hard
 *       per-tick work budget, never O(loaded chunks), and each returns immediately when its
 *       config section is off. Adding a second registration for, say, fallout would mean a
 *       second thing to profile for no benefit — the fallout field is data inside step 2.</li>
 *   <li>All tunables resolve through {@link ConfigManager} before registries are
 *       consulted by gameplay code, so yields are deterministic per install.</li>
 * </ul>
 */
public final class DoomsdayNukes implements ModInitializer {
	public static final String MOD_ID = "doomsday";
	public static final String MOD_NAME = "Doomsday Nukes";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

	/** Server-side singleton; null on a physical client until a world is joined. */
	private static DetonationManager detonations;

	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		// 1 — configuration first: registries read yield/size defaults from it.
		ConfigManager.load();

		// 2 — payloads must be registered before any phase of the network can see them.
		ModPackets.register();

		// 3 — content. Order matters only for cross-references (items -> blocks).
		ModSounds.register();
		ModBlocks.register();
		ModItems.register();
		ModBlockEntities.register();
		ModEntities.register();
		ModStatusEffects.register();
		ModItemGroups.register();

		// 4 — world services (single instance per server, rebuilt on lifecycle events).
		detonations = new DetonationManager();
		DoomsdayCommands.register();
		RadiationManager.init();
		EMPManager.init();

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			detonations.tick(server);
			RadiationManager.tick(server);
			EMPManager.tick(server);
		});

		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			detonations.onServerStart(server);
			RadiationManager.onServerStart(server);
			EMPManager.onServerStart(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			detonations.onServerStop();
			RadiationManager.onServerStop();
			EMPManager.onServerStop();
		});
		// Reconnect / world-swap safety: a device that was mid-arm when the server
		// stopped is resumed from NBT by its BlockEntity, and any detonation that was
		// mid-flight is finished deterministically (world mutation is server-owned).
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, manager, success) -> {
			ConfigManager.load();
			LOGGER.info("Data pack reload complete — Doomsday Nukes re-read its presets.");
		});

		// 5 — per-player sync. Join is where a reconnecting player learns about live detonations
		// and the contamination field around them; leave drops the transient tracking state.
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN
			.register((handler, sender, server) -> {
				ServerPlayerEntity player = handler.getPlayer();
				if (detonations != null) {
					detonations.syncPlayer(player);
				}
				ModPackets.syncOnJoin(player);
				RadiationManager.onJoin(player);
			});
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT
			.register((handler) -> RadiationManager.onDisconnect(handler.getPlayer().getUuid()));

		LOGGER.info("{} {} initialised (Fabric / MC 1.21.1). {} detonation presets loaded.",
			MOD_NAME, version(), com.doomsday.nukes.detonation.NukePreset.values().length);
	}

	public static String version() {
		return net.fabricmc.loader.api.FabricLoader.getInstance()
			.getModContainer(MOD_ID)
			.map(c -> c.getMetadata().getVersion().getFriendlyString())
			.orElse("dev");
	}

	public static DetonationManager detonations() {
		return detonations;
	}
}
