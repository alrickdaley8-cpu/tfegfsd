package com.doomsday.nukes.registry;

import com.doomsday.nukes.DoomsdayNukes;
import com.doomsday.nukes.entity.CloudAnchorEntity;
import com.doomsday.nukes.entity.FalloutEntity;
import com.doomsday.nukes.entity.FireballEntity;
import com.doomsday.nukes.entity.MushroomCloudEntity;
import com.doomsday.nukes.entity.ShockwaveEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

/**
 * The visual entity types. These are <b>client-only renderable objects</b> that happen to reuse
 * the entity renderer plumbing (frustum culling, distance sorting, tick loop) instead of fighting
 * it.
 *
 * <h2>Why the server never spawns them</h2>
 * Spawning a {@code MushroomCloudEntity} server-side would mean chunk persistence, tracking
 * ranges, spawn packets, save-file growth and an easy way to desync a 90-second animation.
 * Instead the server sends one detonation packet, and the client instantiates its own visuals.
 * The entity types are registered anyway because that is what makes
 * {@code EntityRendererRegistry.register} and {@code FabricModelLayers} work with them.
 *
 * <h2>Why this is not "thousands of entities"</h2>
 * It is at most {@code maxVisualEntities} (default 24) for the <em>entire session</em>, pooled and
 * reused: one fireball, one shockwave, one cloud, one fallout field and one cloud-shadow anchor
 * per live detonation. A Tsar Bomba is therefore 5 objects, not 5×10^4.
 */
public final class ModEntities {
	public static final EntityType<FireballEntity> FIREBALL = register("fireball",
		EntityType.Builder.<FireballEntity>create(FireballEntity::new, SpawnGroup.MISC)
			.dimensions(1.0F, 1.0F)
			.maxTrackingRange(32)
			.trackingTickInterval(20)
			// No fireImmune(): 1.21.1 dropped it from the builder, and these entities are never
			// spawned server-side, so there is no damage source to be immune to in the first place.
			.build("doomsday:fireball"));

	public static final EntityType<ShockwaveEntity> SHOCKWAVE = register("shockwave",
		EntityType.Builder.<ShockwaveEntity>create(ShockwaveEntity::new, SpawnGroup.MISC)
			.dimensions(1.0F, 1.0F)
			.maxTrackingRange(32)
			.trackingTickInterval(20)
			// No fireImmune(): 1.21.1 dropped it from the builder, and these entities are never
			// spawned server-side, so there is no damage source to be immune to in the first place.
			.build("doomsday:shockwave"));

	public static final EntityType<MushroomCloudEntity> MUSHROOM_CLOUD = register("mushroom_cloud",
		EntityType.Builder.<MushroomCloudEntity>create(MushroomCloudEntity::new, SpawnGroup.MISC)
			.dimensions(1.0F, 1.0F)
			.maxTrackingRange(64)
			.trackingTickInterval(20)
			// No fireImmune(): 1.21.1 dropped it from the builder, and these entities are never
			// spawned server-side, so there is no damage source to be immune to in the first place.
			.build("doomsday:mushroom_cloud"));

	public static final EntityType<FalloutEntity> FALLOUT = register("fallout",
		EntityType.Builder.<FalloutEntity>create(FalloutEntity::new, SpawnGroup.MISC)
			.dimensions(1.0F, 1.0F)
			.maxTrackingRange(48)
			.trackingTickInterval(20)
			// No fireImmune(): 1.21.1 dropped it from the builder, and these entities are never
			// spawned server-side, so there is no damage source to be immune to in the first place.
			.build("doomsday:fallout"));

	/**
	 * Zero-size anchor for the cloud shadow and the heat-haze pass. It carries no geometry: it
	 * exists so those two effects have a camera-relative position and a lifetime, through the
	 * normal render dispatch, instead of a hand-rolled world-render hook.
	 */
	public static final EntityType<CloudAnchorEntity> CLOUD_ANCHOR = register("cloud_anchor",
		EntityType.Builder.<CloudAnchorEntity>create(CloudAnchorEntity::new, SpawnGroup.MISC)
			.dimensions(0.01F, 0.01F)
			.maxTrackingRange(64)
			.trackingTickInterval(20)
			// No fireImmune(): 1.21.1 dropped it from the builder, and these entities are never
			// spawned server-side, so there is no damage source to be immune to in the first place.
			.build("doomsday:cloud_anchor"));

	private ModEntities() {
	}

	private static <T extends net.minecraft.entity.Entity> EntityType<T> register(
		String name, EntityType<T> type) {
		return Registry.register(Registries.ENTITY_TYPE, DoomsdayNukes.id(name), type);
	}

	/** Called from the entrypoint so ordering is explicit. */
	public static void register() {
		if (MUSHROOM_CLOUD == null) {
			throw new IllegalStateException("Visual entity types failed to register");
		}
	}
}
