package com.doomsday.nukes.entity;

import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.config.DoomsdayConfig;
import com.doomsday.nukes.util.MathUtil;
import net.minecraft.entity.EntityType;
import net.minecraft.world.World;

/**
 * Stage 3 — the shockwave: a condensation ring travelling outward at a configurable speed.
 *
 * <h2>Radius comes from speed, not from lifetime</h2>
 * {@code shockwaveSpeed} is in blocks/second, so the visible front is at
 * {@code speed * elapsedSeconds}. The wall radius is therefore a function of time and of the yield
 * derived {@code shockwaveRadius} cap — not of {@code lifetime}, which only decides when the entity
 * is released. That is the physically meaningful order (a wave does not stop because an animation
 * ended), and it means the front position is identical for two observers at different frame rates.
 *
 * <p>The destructive wave the server ran (block removal, knockback) uses the *same* speed and
 * radius through {@code Detonation}'s instantaneous pass; the visible ring is a lag-free echo of
 * it, which is why no damage packet exists for this stage.</p>
 */
public class ShockwaveEntity extends VisualEffectEntity {
	/** Ground-truth wavefront distance so far, in blocks. */
	private double travelled;
	private int ringCount = 3;
	private double thickness = 6.0D;

	public ShockwaveEntity(EntityType<? extends ShockwaveEntity> type, World world) {
		super(type, world);
	}

	public void applyStageConfig() {
		DoomsdayConfig c = ConfigManager.get();
		this.lifetime = Math.max(20, ConfigManager.ticks(c.shockwaveDurationSeconds));
		this.ringCount = Math.max(1, Math.min(6, c.shockwaveRingCount));
		// A wall roughly 2 % of the distance travelled, floored at 6 blocks: near the device the
		// ring is a knife edge, far away it is a broad front, as in footage.
		this.thickness = 6.0D;
	}

	@Override
	public void tick() {
		if (getWorld().isClient) {
			// The server never drives visuals (see ModEntities), so integration happens only where
			// something is actually drawn. One config read per tick per live wave: cheap.
			DoomsdayConfig c = ConfigManager.get();
			this.travelled += Math.max(1.0D, c.shockwaveSpeed) / 20.0D;
			// A front that decelerates slightly once it becomes wind, which is what a real blast
			// wave does; the factor is deliberately close to 1 so the ring still reads as supersonic.
			double p = MathUtil.clamp01(this.age / (double) Math.max(1, this.lifetime));
			this.travelled *= 1.0D - 0.015D * p;
		}
		super.tick();
	}

	/** Outer radius of the visible front, capped by the server's own wave radius. */
	public double frontRadius() {
		double cap = this.radius > 1.0D ? this.radius * 1.35D : Double.MAX_VALUE;
		return Math.min(this.travelled, cap);
	}

	/** Inner radius: the wall thickness widens with distance (see applyStageConfig). */
	public double innerRadius() {
		return Math.max(0.0D, frontRadius() - wallThickness());
	}

	public double wallThickness() {
		return thickness + frontRadius() * 0.02D;
	}

	public int ringCount() {
		return ringCount;
	}

	/** 0..1 opacity of the whole wave, fading over the second half of the animation. */
	public double alpha() {
		float p = progress();
		if (p < 0.15D) {
			return p / 0.15D;
		}
		double t = (p - 0.15D) / 0.85D;
		return Math.max(0.0D, 1.0D - t * t * t);
	}

	@Override
	protected void onExpired() {
	}
}
