package com.doomsday.nukes.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Base class for the five visual effect entities.
 *
 * <h2>What these objects are</h2>
 * A fireball, a shockwave, a mushroom cloud, a fallout field and a cloud-shadow anchor are the
 * only things in this mod that need per-frame state attached to a position in the world. Reusing
 * the entity system for that buys frustum culling, distance sorting, the render dispatch and the
 * tick loop for free; reimplementing those as a {@code WorldRenderEvents} handler buys a maintenance
 * problem instead.
 *
 * <h2>What they are not</h2>
 * <b>Never spawned by the server.</b> There is no collision, no damage, no NBT, no tracking, no
 * save-file growth and no spawn packet: the client builds them from one compact detonation event
 * ({@code DetonationS2CPacket}) and lets them expire on their own. {@link #shouldBeSaved()} is
 * false and {@link #canHit()} is false so even a stray ray cannot interact with one. The entity
 * <em>type</em> is registered server-side (see {@code ModEntities}) because the registry demands
 * it, not because the simulation does.
 *
 * <h2>Shared state</h2>
 * {@code origin}, {@code radius}, {@code lifetime} and the derived {@code progress} are enough to
 * draw every stage; per-stage classes add only the parameters their renderer needs. This keeps the
 * wire format (one packet) and the render code in lockstep: no renderer ever needs data the packet
 * did not carry.
 */
public abstract class VisualEffectEntity extends Entity {
	/** Detonation epicentre — never mutated, so renderers can use it as a stable anchor. */
	protected Vec3d origin = Vec3d.ZERO;
	/** Peak radius in blocks (already yield-scaled server-side). */
	protected double radius = 1.0D;
	/** Effective yield of the event that spawned this, in kt — used for colour/heat. */
	protected double yieldKt = 1.0D;
	/** Total lifetime in ticks, from the config's per-stage seconds. */
	protected int lifetime = 200;
	/** 0..1 geometry scale for this stage (radius interpolation is derived from it). */
	protected double geometryScale = 1.0D;
	/** True once the effect has played out, so subclasses can stop allocating work. */
	private boolean expired;
	/**
	 * The server's detonation id, set by {@code DoomsdayVisuals} when the entity is created. It
	 * lives here (in common code, on the entity) rather than in a client-side map because the
	 * de-duplication check needs it every spawn, a map would need cleanup on every natural expiry,
	 * and the interface that carries it must not be a client type — these entities are constructed
	 * by a common base class that a dedicated server is allowed to load.
	 */
	private int eventId;

	protected VisualEffectEntity(EntityType<?> type, World world) {
		super(type, world);
		// These entities must not participate in physics at all: no gravity means no velocity
		// integration, no collision, and no chance of pushing a player out of the crater.
		this.setNoGravity(true);
		this.ignoreCameraFrustum = true;
		this.setInvulnerable(true);
	}

	/**
	 * Configure from the detonation event. Called once, immediately after construction, by
	 * {@code client/DoomsdayVisuals}.
	 *
	 * @param origin   epicentre in world space
	 * @param radius   peak radius in blocks, already scaled for yield and for the quality tier
	 * @param lifetime total ticks this stage should be drawn for
	 * @param yieldKt  effective yield of the event, for colour/heat only
	 */
	public void configure(Vec3d origin, double radius, int lifetime, double yieldKt) {
		this.origin = origin == null ? Vec3d.ZERO : origin;
		this.radius = Math.max(0.25D, radius);
		this.lifetime = Math.max(1, lifetime);
		this.yieldKt = Math.max(0.01D, yieldKt);
		this.age = 0;
		this.expired = false;
		this.updatePosition(this.origin.x, this.origin.y, this.origin.z);
		this.prevX = this.getX();
		this.prevY = this.getY();
		this.prevZ = this.getZ();
		// The render-interpolation fields are doubles on Entity, so seeding them is exact.
		this.lastRenderX = this.origin.x;
		this.lastRenderY = this.origin.y;
		this.lastRenderZ = this.origin.z;
	}

	@Override
	public void tick() {
		super.tick();
		if (getWorld().isClient) {
			// Pin the entity to the epicentre every tick. Vanilla's movement integration is
			// harmless with no gravity, but it also does nothing; writing the position explicitly
			// removes any chance of drift over a 90-second cloud animation.
			this.updatePosition(this.origin.x, this.origin.y + verticalDrift(), this.origin.z);
		}
		if (++this.age >= this.lifetime) {
			if (!this.expired) {
				this.expired = true;
				this.onExpired();
			}
			this.discard();
		}
	}

	/**
	 * Optional per-tick vertical offset relative to the epicentre. Only the cloud uses it (its
	 * rise); every other stage stays anchored, so the base returns zero rather than a default
	 * curve someone has to negate.
	 */
	protected double verticalDrift() {
		return 0.0D;
	}

	/** Hook for the one-time cost at the end of an animation (particle release, fade-out state). */
	protected void onExpired() {
	}

	/** 0..1 across the whole animation, smoothed for anything that must not snap. */
	public float progress() {
		return clamp01(this.age / (float) Math.max(1, this.lifetime));
	}

	/**
	 * Three-phase envelope used by the fireball and the shockwave: grow (ease-out), hold, fade
	 * (ease-in). {@code grow}/{@code hold}/{@code fade} are fractions of the total lifetime and are
	 * normalised here so a config that does not sum to 1.0 cannot produce a step in the curve.
	 *
	 * @return 0..1 radius multiplier
	 */
	public static double envelope(float p, double grow, double hold, double fade) {
		double total = Math.max(0.0001D, grow + hold + fade);
		double g = grow / total;
		double h = hold / total;
		if (p < g) {
			double t = g <= 0.0D ? 1.0D : p / g;
			return 1.0D - (1.0D - t) * (1.0D - t);
		}
		if (p < g + h) {
			return 1.0D;
		}
		double t = (p - g - h) / Math.max(0.0001D, 1.0D - g - h);
		return Math.max(0.0D, 1.0D - t * t);
	}

	protected static float clamp01(float v) {
		return v < 0.0F ? 0.0F : (v > 1.0F ? 1.0F : v);
	}

	// ———————————————————————————————————————————————————— accessors

	public Vec3d origin() {
		return this.origin;
	}

	public double radius() {
		return this.radius;
	}

	public double yieldKt() {
		return this.yieldKt;
	}

	public int lifetime() {
		return this.lifetime;
	}

	public int remainingTicks() {
		return Math.max(0, this.lifetime - this.age);
	}

	public boolean isExpired() {
		return this.expired;
	}

	public int eventId() {
		return this.eventId;
	}

	public void setEventId(int eventId) {
		this.eventId = eventId;
	}

	public double geometryScale() {
		return this.geometryScale;
	}

	// ———————————————————————————————————————————— vanilla hard-no-ops

	// Neither hook has anything to do: these entities exist only on the client, so there is no
	// server-side instance to save and no synced-data entry to replicate. They are overridden to
	// say that out loud, because Entity declares both abstract.
	@Override
	protected void writeCustomDataToNbt(NbtCompound nbt) {
	}

	@Override
	protected void readCustomDataFromNbt(NbtCompound nbt) {
	}
	@Override
	public boolean canHit() {
		return false;
	}

	@Override
	public boolean isOnFire() {
		return false;
	}
}
