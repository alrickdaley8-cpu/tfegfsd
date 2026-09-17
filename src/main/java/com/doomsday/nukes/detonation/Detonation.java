package com.doomsday.nukes.detonation;

import com.doomsday.nukes.DoomsdayNukes;
import com.doomsday.nukes.block.FlashLightBlock;
import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.config.DoomsdayConfig;
import com.doomsday.nukes.effect.RadiationManager;
import com.doomsday.nukes.network.ModPackets;
import com.doomsday.nukes.network.packet.DetonationS2CPacket;
import com.doomsday.nukes.network.packet.StageChangeS2CPacket;
import com.doomsday.nukes.util.MathUtil;
import com.doomsday.nukes.world.CraterGenerator;
import com.doomsday.nukes.world.DetonationTerrainPlan;
import com.doomsday.nukes.world.EMPManager;
import com.doomsday.nukes.world.TerrainWorkQueue;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One in-flight detonation. A {@code Detonation} is a <b>state machine with a budget</b>:
 * it knows its absolute timeline, its current stage, and the bounded work queues that
 * each stage feeds. It never touches a block or an entity outside of those queues.
 *
 * <h2>What runs where</h2>
 * <ul>
 *   <li>Server (this class): crater/terrain mutation, contamination registration, EMP
 *       zones, entity damage and knockback, flash blindness, stage broadcast.</li>
 *   <li>Client: everything that only <em>looks</em> like something. Clients build pooled
 *       visual entities from {@link DetonationS2CPacket} and derive their stage from the
 *       shared world clock, so no per-tick traffic exists.</li>
 * </ul>
 *
 * <h2>Bounded work — one {@link #tick} call performs at most</h2>
 * <ol>
 *   <li>one stage-advance comparison against the timeline,</li>
 *   <li>at most {@code terrainBlocksPerTick} block writes across at most
 *       {@code terrainMaxChunksPerTick} <em>already loaded</em> chunks,</li>
 *   <li>one annulus query for the shockwave front (each entity is affected once, ever —
 *       tracked in {@link #affected}),</li>
 *   <li>zero allocations in the steady state apart from the entity query itself.</li>
 * </ol>
 * A 100 kt detonation therefore degrades over {@code terrainMaxTicks} ticks instead of
 * freezing the server inside one.
 */
public final class Detonation {
	private final int id;
	private final NukePreset preset;
	private ConfigManager.ResolvedTuning tuning;
	private final DetonationTimeline timeline;
	private final BlockPos originBlock;
	private final Vec3d origin;
	private final long startWorldTime;
	private double yieldKt;

	private DetonationStage stage = DetonationStage.FLASH;
	private int lastStageBroadcastIndex = -1;
	private long terrainTicks;
	private boolean terrainQueued;
	private boolean radiationQueued;
	private boolean empQueued;
	private boolean flashApplied;
	private boolean waveFinished;
	private boolean aftermathSent;
	private boolean finished;
	private DetonationTerrainPlan plan;

	/** Bounded, chunk-aware terrain edit queue owned by this detonation. */
	private final TerrainWorkQueue terrain = new TerrainWorkQueue();
	/** Entity ids already hit by the destructive front (one impulse per entity ever). */
	private final Set<Integer> affected = new HashSet<>(64);

	public Detonation(int id, NukePreset preset, ConfigManager.ResolvedTuning tuning,
					  Vec3d origin, BlockPos originBlock, long startWorldTime, double yieldKt) {
		this.id = id;
		this.preset = preset;
		this.tuning = tuning;
		this.origin = origin;
		this.originBlock = originBlock;
		this.startWorldTime = startWorldTime;
		this.yieldKt = Math.max(0.05D, yieldKt);
		this.timeline = new DetonationTimeline(ConfigManager.get(), tuning);
	}

	// ——————————————————————————————————————————————————— accessors

	public int id() {
		return id;
	}

	public NukePreset preset() {
		return preset;
	}

	public DetonationTimeline timeline() {
		return timeline;
	}

	public Vec3d origin() {
		return origin;
	}

	public BlockPos originBlock() {
		return originBlock;
	}

	public long startWorldTime() {
		return startWorldTime;
	}

	public double yieldKt() {
		return yieldKt;
	}

	public DetonationStage stage() {
		return stage;
	}

	public ConfigManager.ResolvedTuning tuning() {
		return tuning;
	}

	public boolean isFinished() {
		return finished;
	}

	/**
	 * Absorbs another device's yield. Radii grow by the cube root of the yield ratio (the
	 * same law the preset uses), and contamination duration by its square root, so a merge
	 * makes a bigger, longer event — never an exponentially bigger one.
	 *
	 * @return the new effective yield in kilotonnes
	 */
	public double mergeYield(double incomingKt, NukePreset other) {
		DoomsdayConfig c = ConfigManager.get();
		double merged = Math.min(Math.max(0.5D, c.maxEffectiveYieldKt),
			yieldKt + Math.max(0.0D, incomingKt));
		if (merged <= yieldKt + 1.0E-3D) {
			return yieldKt;
		}
		double g = Math.cbrt(merged / Math.max(0.05D, yieldKt));
		ConfigManager.ResolvedTuning base = tuning;
		this.tuning = new ConfigManager.ResolvedTuning(
			merged,
			base.blastRadius() * g,
			base.fireballRadius() * g,
			base.shockwaveRadius() * g,
			base.craterRadius() * g,
			Math.max(0.05D, base.cloudScale() * g),
			base.radiationSeconds() * Math.sqrt(g));
		this.yieldKt = merged;
		if (c.verboseLogging) {
			DoomsdayNukes.LOGGER.info("[detonation {}] merged {} kt -> {} kt (radii x{})",
				id, String.format(java.util.Locale.ROOT, "%.1f", incomingKt),
				String.format(java.util.Locale.ROOT, "%.1f", merged),
				String.format(java.util.Locale.ROOT, "%.2f", g));
		}
		return merged;
	}

	public int affectedCount() {
		return affected.size();
	}

	public DetonationTerrainPlan terrainPlan() {
		return plan;
	}

	public TerrainWorkQueue terrainQueue() {
		return terrain;
	}

	public float timeAt(long worldTime) {
		return timeline.timeAt(startWorldTime, worldTime);
	}

	// ——————————————————————————————————————————————————— tick driver

	void tick(ServerWorld world) {
		long worldTime = world.getTime();
		float t = timeAt(worldTime);
		DoomsdayConfig c = ConfigManager.get();

		// 1 — stage advance
		DetonationStage next = timeline.stageAt(t);
		if (next != stage) {
			stage = next;
			onStageEnter(world, t, c);
		}

		// 2 — one-shot side effects, each gated by a boolean so it fires exactly once
		if (!flashApplied && t >= timeline.flashStart) {
			flashApplied = true;
			applyFlash(world, t, c);
		}
		if (!terrainQueued && t >= timeline.fireballStart) {
			terrainQueued = true;
			queueTerrainEffects(world, c);
		}
		if (!empQueued && t >= timeline.shockwaveStart && c.empEnabled) {
			empQueued = true;
			EMPManager.addZone(world, origin,
				Math.max(8.0D, tuning.shockwaveRadius()) * c.empRadiusMultiplier,
				DoomsdayConfig.ticks(c.empSeconds));
		}
		if (!radiationQueued && t >= timeline.falloutStart && c.radiationEnabled) {
			radiationQueued = true;
			RadiationManager.contaminate(world, origin, tuning, c);
		}

		// 3 — terrain budget flush
		if (!terrain.isEmpty()) {
			terrainTicks++;
			terrain.flush(world, c.terrainBlocksPerTick, c.terrainMaxChunksPerTick);
			if (terrainTicks > c.terrainMaxTicks) {
				if (c.verboseLogging && terrain.remaining() > 0) {
					DoomsdayNukes.LOGGER.warn(
						"Detonation {} hit terrainMaxTicks ({}); {} queued block edits dropped to protect the tick.",
						id, c.terrainMaxTicks, terrain.remaining());
				}
				terrain.clear();
			}
		}

		// 4 — destructive front sweep
		if (!waveFinished && t >= timeline.shockwaveStart) {
			sweepShockwave(world, t, c);
		}

		// 5 — completion
		if (t >= timeline.totalLength && terrain.isEmpty()) {
			finished = true;
		}
	}

	private void onStageEnter(ServerWorld world, float t, DoomsdayConfig c) {
		if (c.verboseLogging) {
			DoomsdayNukes.LOGGER.info("[detonation {}] stage '{}' at t={}s (origin {}, yield {} kt)",
				id, stage.key(), String.format(java.util.Locale.ROOT, "%.2f", t),
				originBlock.toShortString(), String.format(java.util.Locale.ROOT, "%.1f", yieldKt));
		}
		if (stage == DetonationStage.FALLOUT && c.atmosphericAftermath && !aftermathSent) {
			aftermathSent = true;
			ModPackets.broadcastAftermath(world, origin, timeline.aftermathLength, c.skyDarkness);
		}
		if (stage.index() != lastStageBroadcastIndex) {
			lastStageBroadcastIndex = stage.index();
			ModPackets.sendToTracking(world, origin,
				(float) Math.min(16000.0D, c.soundDistance * 6.0D),
				new StageChangeS2CPacket(id, stage.index(), t, originBlock));
		}
	}

	// ———————————————————————————————————————————————————— stage 1

	/**
	 * Flash consequences. Two independent things happen here:
	 * <ol>
	 *   <li><b>Gameplay:</b> distance-attenuated Blindness, reduced when the player wears
	 *       Hazmat Goggles (never absolute unless configured to 0).</li>
	 *   <li><b>Lighting:</b> a short-lived emissive air-substitute block at the epicentre,
	 *       which the vanilla/Sodium light engine propagates normally. This is the
	 *       dynamic-light fallback path — no third-party mod required, and no lighting
	 *       code is replaced.</li>
	 * </ol>
	 */
	private void applyFlash(ServerWorld world, float t, DoomsdayConfig c) {
		if (c.flashEnabled) {
			double radius = Math.max(16.0D, c.flashBlindnessRadius
				* Math.max(1.0D, NukePreset.geometryScaleFor(yieldKt)));
			for (ServerPlayerEntity player : world.getPlayers()) {
				if (player.isSpectator() || !player.isAlive()) {
					continue;
				}
				double d = player.getPos().distanceTo(origin);
				if (d > radius) {
					continue;
				}
				double atten = MathUtil.attenuation(d, 24.0D, radius, c.flashFalloffExponent);
				if (atten < 0.05D) {
					continue;
				}
				boolean goggles = com.doomsday.nukes.item.HazmatGear.wearsGoggles(player);
				int baseSeconds = goggles ? c.flashBlindnessGoggledSeconds : c.flashBlindnessSeconds;
				// Goggles reduce (never absolutely), and distance reduces further.
				double factor = atten * (goggles ? 0.35D : 1.0D);
				int seconds = (int) Math.round(baseSeconds * factor);
				if (seconds <= 0) {
					continue;
				}
				player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS,
					MathUtil.clamp(seconds * 20, 1, 1200), 0, false, true, true));
			}
		}

		if (c.flashEnabled && c.flashLightFallback) {
			int lightTicks = Math.max(1, DoomsdayConfig.ticks(c.flashLightSeconds));
			// A handful of emitters around the epicentre give a *volume* of light instead of a
			// single bright point, without touching thousands of blocks: placeCluster writes at
			// most 7 states (epicentre + 6 neighbours), each air-gated and self-removing through its
			// own scheduled tick. That is the whole lighting story — no dynamic-light API, no
			// per-chunk relight of a 500-block crater, and nothing left behind if the server stops.
			int spread = (int) MathUtil.clamp(tuning.fireballRadius() * 0.34D, 1.0D, 12.0D);
			int placed = FlashLightBlock.placeCluster(world, originBlock.up(2), lightTicks, spread);
			if (c.verboseLogging && placed == 0) {
				DoomsdayNukes.LOGGER.info("[detonation {}] flash light had nowhere to go "
					+ "(epicentre is not air at {})", id, originBlock.toShortString());
			}
		}
	}

	// ———————————————————————————————————————————————————— stage 2/5

	private void queueTerrainEffects(ServerWorld world, DoomsdayConfig c) {
		if (!c.griefingEnabled) {
			// Visual-only mode: zero block writes. This is the "no griefing" contract, and
			// it is enforced here — one place — rather than inside the generator, so no
			// code path can reach the world without passing this test.
			return;
		}
		DetonationTerrainPlan p = CraterGenerator.buildPlan(origin, tuning, yieldKt, c);
		if (p == null) {
			return;
		}
		this.plan = p;
		int queued = CraterGenerator.enqueue(world, terrain, p);
		if (c.verboseLogging) {
			DoomsdayNukes.LOGGER.info("[detonation {}] queued {} terrain edits — {}",
				id, queued, p);
		}
	}

	// ———————————————————————————————————————————————————— stage 3

	/**
	 * Destructive shockwave: annulus query so the front only interacts with entities it is
	 * <em>currently crossing</em>, and {@link #affected} guarantees exactly one impulse per
	 * entity for the whole event.
	 *
	 * <p>Falloff is {@code force = k * yield / max(d^2, minD^2)} — singular-free by
	 * construction, so an entity standing at the epicentre receives {@code k*yield/minD^2},
	 * a finite, configurable value instead of an infinity that launches it into space.</p>
	 */
	private void sweepShockwave(ServerWorld world, float t, DoomsdayConfig c) {
		float local = t - timeline.shockwaveStart;
		if (local > timeline.shockwaveLength) {
			waveFinished = true;
			affected.clear();
			return;
		}
		if (!c.shockwaveDestructive) {
			return;
		}

		double radius = timeline.waveRadiusAt(t);
		double band = Math.max(5.0D, c.shockwaveSpeed / 20.0D * 1.5D);
		double inner = Math.max(0.0D, radius - band);
		double outer = radius + band * 0.35D;
		float box = (float) (outer + 3.0D);

		Box area = new Box(origin.x - box, origin.y - box * 0.75D, origin.z - box,
			origin.x + box, origin.y + box * 1.15D, origin.z + box);
		List<LivingEntity> hits = world.getEntitiesByClass(LivingEntity.class, area,
			e -> e.isAlive() && !e.isRemoved() && !affected.contains(e.getId()));
		if (hits.isEmpty()) {
			return;
		}

		double yieldTerm = Math.max(0.05D, yieldKt / 20.0D);
		double minD = Math.max(0.5D, c.knockbackMinDistance);
		double minD2 = minD * minD;

		for (int i = hits.size() - 1; i >= 0; i--) {
			LivingEntity e = hits.get(i);
			double dx = e.getX() - origin.x;
			double dy = e.getEyeY() - origin.y;
			double dz = e.getZ() - origin.z;
			double flat = Math.sqrt(dx * dx + dz * dz);
			// Only entities the front is actually on top of, and only from ground level.
			if (flat < inner || flat > outer) {
				continue;
			}
			if (Math.abs(dy) > Math.max(12.0D, radius * 0.35D)) {
				continue;
			}
			affected.add(e.getId());

			double d2 = Math.max(minD2, flat * flat + dy * dy);
			double force = Math.min(c.knockbackPeak, c.knockbackPeak * yieldTerm * (36.0D / d2));
			if (force <= 0.0005D) {
				continue;
			}
			double inv = flat < 1.0E-4D ? 0.0D : 1.0D / flat;
			Vec3d push = new Vec3d(dx * inv * force, force * c.knockbackLift + force * 0.12D,
				dz * inv * force);
			e.setVelocity(e.getVelocity().add(push));
			e.velocityModified = true;

			// Damage uses the same normalised falloff, and is skipped for creative/spectator
			// players so an operator watching a test shot is not killed by the frame they
			// happen to stand in.
			if (!(e instanceof ServerPlayerEntity p && (p.isCreative() || p.isSpectator()))) {
				double dmgScale = MathUtil.attenuation(flat, minD, Math.max(outer, radius * 1.6D), 2.0D);
				float dmg = (float) (c.shockwaveDamage * dmgScale * Math.min(3.0D, yieldTerm));
				if (dmg > 0.35F) {
					e.damage(world.getDamageSources().explosion(null, null), dmg);
				}
			}

			// Glass shattering and leaf stripping for this annulus are already covered by
			// the terrain plan's wave band (see queueTerrainEffects), which is budgeted.
			// Doing a second per-entity write here would double the block work for nothing.
		}
	}
}
