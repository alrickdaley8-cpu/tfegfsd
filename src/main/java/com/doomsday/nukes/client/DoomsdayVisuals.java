package com.doomsday.nukes.client;

import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.config.DoomsdayConfig;
import com.doomsday.nukes.detonation.NukePreset;
import com.doomsday.nukes.entity.CloudAnchorEntity;
import com.doomsday.nukes.entity.FalloutEntity;
import com.doomsday.nukes.entity.FireballEntity;
import com.doomsday.nukes.entity.MushroomCloudEntity;
import com.doomsday.nukes.entity.ShockwaveEntity;
import com.doomsday.nukes.entity.VisualEffectEntity;
import com.doomsday.nukes.network.packet.DetonationS2CPacket;
import com.doomsday.nukes.registry.ModEntities;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side owner of every visual entity a detonation produces.
 *
 * <h2>Why the client spawns them</h2>
 * The server sends one {@link DetonationS2CPacket} with the whole event (timing windows, radii,
 * flags). This class turns that into at most {@code maxVisualEntities} (default 24) local entities.
 * No entity is ever tracked, saved, or respawned: a client that joins 40 s late receives the same
 * packet from {@code ModPackets.syncOnJoin} and reconstructs the same animation from
 * {@code worldTime - startWorldTime}. That symmetry — "the packet is the state" — is why a
 * mid-event join looks correct instead of showing a frozen or duplicated fireball.
 *
 * <h2>Pooling and the cap</h2>
 * Entities are kept in one list and expired by their own lifetime. The cap is enforced before
 * spawning by evicting the oldest, most distant effect — so a 6-detonation cascade degrades to
 * "the nearest six look right" rather than "the frame rate dies". Nothing is discarded while a
 * renderer still holds a reference to it, because renderers only read the entity for one frame.
 */
public final class DoomsdayVisuals {
	private static final List<Entity> LIVE = new ArrayList<>(32);
	private static int spawned;
	private static int culled;
	private static int mergedAway;

	private DoomsdayVisuals() {
	}

	/**
	 * Build the visual set for a detonation. Safe to call twice for the same id (a join sync
	 * followed by a broadcast): the second call is dropped, which is what keeps a relog from
	 * producing two clouds over one crater.
	 */
	public static synchronized void onDetonation(DetonationS2CPacket p) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.world == null) {
			return;
		}
		int id = p.detonationId();
		for (Entity e : LIVE) {
			if (e instanceof VisualEffectEntity v && v.eventId() == id) {
				// Same event, second packet (join-sync racing a broadcast): ignore the duplicate.
				mergedAway++;
				return;
			}
		}
		DoomsdayConfig c = ConfigManager.get();
		ClientWorld world = client.world;
		Vec3d origin = new Vec3d(p.x(), p.y(), p.z());
		NukePreset preset = NukePreset.byOrdinalOrFallback(p.presetOrdinal());
		double geometry = preset.geometryScale(1.0D);
		double yieldKt = Math.max(0.01D, p.yieldKt());

		// Distance gate first: nothing is created for an event the player will never see.
		ClientPlayerEntity player = client.player;
		double distance = player == null ? 0.0D : origin.distanceTo(player.getEyePos());
		double viewDistance = Math.max(128.0D, c.cloudVisibilityDistance);
		if (distance > viewDistance) {
			culled++;
			return;
		}

		ClientDetonationState.get().begin(origin, Math.max(p.fireballRadius(), p.shockwaveRadius()),
			yieldKt);

		// — fireball
		if (p.fireballLength() > 0.0F) {
			FireballEntity fireball = new FireballEntity(ModEntities.FIREBALL, world);
			fireball.configure(origin, p.fireballRadius(),
				Math.max(20, ConfigManager.ticks(p.fireballLength())), yieldKt);
			fireball.applyStageConfig();
			add(world, fireball, id);
		}

		// — shockwave
		if (p.shockwaveLength() > 0.0F && p.shockwaveRadius() > 0.5F) {
			ShockwaveEntity wave = new ShockwaveEntity(ModEntities.SHOCKWAVE, world);
			wave.configure(origin, p.shockwaveRadius(),
				Math.max(20, ConfigManager.ticks(p.shockwaveLength())), yieldKt);
			wave.applyStageConfig();
			add(world, wave, id);
		}

		// — mushroom cloud + its shadow/haze anchor
		if (c.cloudEnabled && p.cloudEnabled() && p.cloudLength() > 0.0F) {
			MushroomCloudEntity cloud = new MushroomCloudEntity(ModEntities.MUSHROOM_CLOUD, world);
			cloud.configure(origin, p.craterRadius() * 4.0D * geometry,
				Math.max(60, ConfigManager.ticks(p.cloudLength())), yieldKt);
			cloud.applyStageConfig(Math.max(0.25D, p.cloudScale()));
			add(world, cloud, id);

			CloudAnchorEntity anchor = new CloudAnchorEntity(ModEntities.CLOUD_ANCHOR, world);
			anchor.configure(origin, cloud.capRadius(), Math.max(60,
				ConfigManager.ticks(p.cloudLength() * 1.15F)), yieldKt);
			anchor.applyStageConfig(Math.max(0.25D, p.cloudScale()), cloud.riseHeight(),
				cloud.capRadius());
			add(world, anchor, id);
		}

		// — fallout / ash field
		if (c.falloutEnabled && p.falloutEnabled() && p.falloutLength() > 0.0F) {
			FalloutEntity fallout = new FalloutEntity(ModEntities.FALLOUT, world);
			fallout.configure(origin, Math.max(24.0D, p.craterRadius() * 3.0D),
				Math.max(100, ConfigManager.ticks(p.falloutLength())), yieldKt);
			fallout.applyStageConfig(geometry);
			add(world, fallout, id);
		}

		if (c.verboseLogging) {
			com.doomsday.nukes.DoomsdayNukes.LOGGER.info(
				"visuals for detonation {}: {} live entities (distance {}m)", id, LIVE.size(),
				(int) Math.round(distance));
		}
	}

	/** Advance per-frame client work: pool pruning and particle budget release. */
	public static synchronized void tick() {
		if (LIVE.isEmpty()) {
			return;
		}
		for (int i = LIVE.size() - 1; i >= 0; i--) {
			Entity e = LIVE.get(i);
			if (e.isRemoved()) {
				LIVE.remove(i);
			}
		}
	}

	private static void add(ClientWorld world, Entity entity, int detonationId) {
		DoomsdayConfig c = ConfigManager.get();
		int cap = Math.max(4, c.maxVisualEntities);
		while (LIVE.size() >= cap) {
			// Evict the most distant effect: the near field is where a player is looking.
			int worst = 0;
			double worstDistance = -1.0D;
			for (int i = 0; i < LIVE.size(); i++) {
				double d = LIVE.get(i).squaredDistanceTo(cCentre(world));
				if (d > worstDistance) {
					worstDistance = d;
					worst = i;
				}
			}
			Entity victim = LIVE.remove(worst);
			victim.discard();
			culled++;
		}
		if (entity instanceof VisualEffectEntity v) {
			v.setEventId(detonationId);
		}
		// -1 asks the client world to allocate an id: exactly what vanilla does for client-only
		// effects, and it cannot collide with a server-assigned id.
		world.addEntity(-1, entity);
		LIVE.add(entity);
		spawned++;
	}

	private static Vec3d cCentre(ClientWorld world) {
		MinecraftClient client = MinecraftClient.getInstance();
		return client != null && client.player != null
			? client.player.getPos() : new Vec3d(world.getSpawnPos().getX(), 64.0D,
			world.getSpawnPos().getZ());
	}

	/** A live count for the debug overlay. */
	public static int liveCount() {
		return LIVE.size();
	}

	public static String describe() {
		return "visuals: " + LIVE.size() + " live, " + spawned + " spawned, " + culled
			+ " culled, " + mergedAway + " duplicate events ignored";
	}

	/** Clear everything (config reload, world change, or a hard reset). */
	public static synchronized void clearAll() {
		for (Entity e : LIVE) {
			e.discard();
		}
		LIVE.clear();
		ClientDetonationState.get().clear();
	}

}
