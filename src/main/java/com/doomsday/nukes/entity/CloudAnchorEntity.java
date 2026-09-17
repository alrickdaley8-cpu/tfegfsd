package com.doomsday.nukes.entity;

import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.config.DoomsdayConfig;
import com.doomsday.nukes.util.MathUtil;
import net.minecraft.entity.EntityType;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * The anchor behind the two effects that are not geometry: the cloud's ground shadow and the
 * atmosphere's haze/fog bias.
 *
 * <h2>Why an entity at all</h2>
 * Both effects need a camera-relative position and a lifetime that follows the cloud, and both must
 * be evaluated by whatever pass draws them — the shadow as a screen-space quad in the HUD/GUI layer,
 * the fog as a blend in {@code WorldRendererMixin#updateFogColor}. Attaching that to a zero-size
 * entity means it rides the existing render dispatch and tick loop, gets culled like anything else,
 * and disappears on schedule. The alternatives are a static global (wrong for two simultaneous
 * craters) or a per-frame world scan (a cost the entire rest of this mod is carefully avoiding).
 *
 * <h2>Explicitly not lighting</h2>
 * The shadow is <b>screen-space</b>: it darkens pixels inside a projected ellipse, it never touches
 * block light, sky light, or a chunk's lighting state. A real shadow map for a 3 km cloud would cost
 * more than every other effect in this mod combined and would need a render target per detonation;
 * a soft dark ellipse reads the same at the distances where you notice it at all. This is also why
 * the shadow cannot make a Sodium "lighting engine" desync: there is nothing to desync.
 */
public class CloudAnchorEntity extends VisualEffectEntity {
	private double shadowRadius = 260.0D;
	private double peakDarkness = 0.72D;
	private double hazeDensity = 0.021D;
	private double fogOrangeMix = 0.55D;
	private boolean shadowEnabled = true;
	private Vec3d anchor = Vec3d.ZERO;

	public CloudAnchorEntity(EntityType<? extends CloudAnchorEntity> type, World world) {
		super(type, world);
	}

	public void applyStageConfig(double geometryScaleForYield, double capHeight, double capRadius) {
		DoomsdayConfig c = ConfigManager.get();
		this.geometryScale = Math.max(0.25D, geometryScaleForYield);
		this.lifetime = Math.max(60, ConfigManager.ticks(c.cloudLifetimeSeconds * 1.15F));
		this.shadowRadius = Math.max(16.0D, capRadius * 1.9D * this.geometryScale);
		this.peakDarkness = MathUtil.clamp(c.skyDarkness, 0.0D, 0.95D);
		this.hazeDensity = Math.max(0.0D, c.hazeDensity);
		this.fogOrangeMix = MathUtil.clamp(c.fogOrangeMix, 0.0D, 1.0D);
		this.shadowEnabled = c.cloudShadow;
		this.anchor = new Vec3d(this.origin.x, this.origin.y + capHeight, this.origin.z);
	}

	/**
	 * 0..1 shadow strength. Rises with the cap (a shadow needs a cloud above it), holds, then
	 * releases over the last fifth of the animation so the ground brightens as the plume thins.
	 */
	public double darkness() {
		float p = progress();
		double in = MathUtil.smoothstep(0.04F, 0.22F, p);
		double out = 1.0D - MathUtil.smoothstep(0.78F, 1.0F, p);
		return peakDarkness * Math.min(in, out);
	}

	/** Extra fog density to add to the world's own, in the same units vanilla uses. */
	public double fogBias() {
		DoomsdayConfig c = ConfigManager.get();
		if (!c.atmosphericAftermath) {
			return 0.0D;
		}
		float p = progress();
		// Recover over atmosphereRecoverSeconds *after* the cloud is gone, which is why the anchor
		// outlives the cloud by 15 % of its lifetime (see applyStageConfig).
		double hold = 1.0D - MathUtil.smoothstep(0.35F, 1.0F, p);
		return hazeDensity * hold;
	}

	/** How far the sky is tinted toward ash-orange rather than just darkened. */
	public double orangeMix() {
		return fogOrangeMix * (0.35D + 0.65D * darkness() / Math.max(0.0001D, peakDarkness));
	}

	public boolean shadowEnabled() {
		return shadowEnabled;
	}

	public double shadowRadius() {
		return shadowRadius;
	}

	/** Ground point the shadow ellipse is drawn around. */
	public Vec3d anchor() {
		return anchor;
	}

	@Override
	protected double verticalDrift() {
		// The anchor is pinned to the epicentre; its own {@code anchor} field carries the cap height,
		// so the shadow must NOT rise with the cloud or it slides off the ground plane.
		return 0.0D;
	}

	@Override
	protected void onExpired() {
	}
}
