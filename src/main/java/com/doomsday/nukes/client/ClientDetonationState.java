package com.doomsday.nukes.client;

import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.config.DoomsdayConfig;
import com.doomsday.nukes.util.MathUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * The single client-side mutable state object for a detonation in progress.
 *
 * <h2>Why one object instead of fields on the renderers</h2>
 * Four independent consumers need the same instantaneous values — the flash whiteout, the HUD lens
 * overlay, the fog/sky mixin and the camera (FOV punch + shake) — and they are spread across
 * different hooks in different phases of the frame. If each computed its own copy from
 * {@code (eventStart, distance)} they would disagree by a frame at best and by a whole animation at
 * worst (different tick deltas, different config reads at different times). One object, advanced
 * exactly once per client tick and once per frame for smooth interpolation, is the fix.
 *
 * <h2>Everything here is derived</h2>
 * No field in this class is authoritative: they are all functions of the event parameters that
 * {@link DoomsdayVisuals#onDetonation} feeds in, and of the local player's distance. That is what
 * makes it safe to reset, to skip, and to leave stale on a server that has no client.
 *
 * <h2>Lifetime</h2>
 * A single instance. It never allocates per frame — the only arrays are three fixed-size floats.
 */
public final class ClientDetonationState {
	/** Total number of frames a full-power flash occupies at the configured quality. */
	public static final float FLASH_MIN_INTERVAL = 0.016F;

	private float flash;
	private float flashWhiteout;
	private float desaturation;
	private float bloom;
	private float chromatic;
	private float shake;
	private float roll;
	private float fovPunch;
	private float hazeFog;
	private float skyDarkness;
	private float orangeMix;
	private float lensDirt;
	/** Seconds since the event started, advanced by {@link #tick(double)}. */
	private double elapsed;
	private int eventSerial;

	/** Vec3d is immutable, so this is replaced rather than mutated: one allocation per event. */
	private Vec3d origin = new Vec3d(0.0D, -1.0E9D, 0.0D);
	private double radius = 1.0D;
	private double yieldKt = 1.0D;
	private double distance = Double.MAX_VALUE;

	private final float[] shakeOffset = new float[3];

	private ClientDetonationState() {
	}

	private static final class Holder {
		static final ClientDetonationState INSTANCE = new ClientDetonationState();
	}

	public static ClientDetonationState get() {
		return Holder.INSTANCE;
	}

	// ————————————————————————————————————————————————— feed from event

	/**
	 * Called from {@code DoomsdayVisuals} when a detonation packet lands. Everything after this is
	 * pure decay: no further packets are required for the animation to complete, so a dropped
	 * stage packet degrades the look instead of freezing it.
	 */
	public void begin(Vec3d origin, double radius, double yieldKt) {
		DoomsdayConfig c = ConfigManager.get();
		this.eventSerial++;
		this.elapsed = 0.0D;
		this.origin = new Vec3d(origin.x, origin.y, origin.z);
		this.radius = Math.max(1.0D, radius);
		this.yieldKt = Math.max(0.01D, yieldKt);
		this.bloom = (float) MathUtil.clamp(c.flashBloom, 1.0D, 3.0D);
		this.chromatic = c.chromaticAberration ? (float) MathUtil.clamp(c.chromaticStrength, 0.0D, 3.0D) : 0.0F;
		this.lensDirt = c.lensDirt ? (float) MathUtil.clamp(c.lensDirtThreshold, 0.0D, 1.0D) : 1.0F;
		this.skyDarkness = (float) MathUtil.clamp(c.skyDarkness, 0.0D, 0.95D);
		this.orangeMix = (float) MathUtil.clamp(c.fogOrangeMix, 0.0D, 1.0D);
		updateDistance();
		// The flash arrives instantly: light does not wait for the next tick.
		this.flashWhiteout = c.flashEnabled ? 1.0F : 0.0F;
		this.flash = this.flashWhiteout;
	}

	/** Advance one *frame* (called from the render-side hooks, so it is vsync-accurate). */
	public void tick(double frameSeconds) {
		DoomsdayConfig c = ConfigManager.get();
		double dt = Math.max(FLASH_MIN_INTERVAL, Math.min(0.5D, frameSeconds));
		this.elapsed += dt;
		updateDistance();

		// — flash: whiteout phase A, then desaturation phase B, both scaled by proximity
		double atten = MathUtil.attenuation(this.distance, 24.0D,
			Math.max(32.0D, c.flashDistance * c.flashTailScale), c.flashFalloffExponent);
		if (!c.flashEnabled) {
			atten = 0.0D;
		}
		float whiteout = Math.max(0.0F, DoomsdayConfig.ticks(c.flashWhiteoutSeconds) / 20.0F);
		float desat = Math.max(0.05F, DoomsdayConfig.ticks(c.flashDesaturateSeconds) / 20.0F);
		float t = (float) this.elapsed;
		float peak = (float) MathUtil.clamp(atten, 0.0D, 1.0D);
		if (t <= whiteout) {
			this.flashWhiteout = peak;
			this.desaturation = peak;
		} else if (t <= whiteout + desat) {
			this.flashWhiteout = 0.0F;
			this.desaturation = peak * (1.0F - (t - whiteout) / desat);
		} else {
			this.flashWhiteout = 0.0F;
			this.desaturation = 0.0F;
		}
		// A short extra bloom tail so the screen "recovers" like a sensor rather than snapping.
		float bloomTail = (float) MathUtil.decay(Math.max(0.0D, t - whiteout) / Math.max(0.5D, desat), 2.2D);
		this.bloom = 1.0F + (float) MathUtil.clamp(c.flashBloom - 1.0D, 0.0D, 2.0D) * peak * bloomTail;
		this.flash = Math.max(this.flashWhiteout, this.desaturation * 0.65F);

		// — camera: shake over cameraShakeSeconds, full strength inside cameraShakeFullRadius
		double shakeFull = Math.max(4.0D, c.cameraShakeFullRadius);
		double shakeMax = Math.max(shakeFull + 16.0D, c.cameraShakeMaxRadius);
		double shakeAtten = MathUtil.attenuation(this.distance, shakeFull, shakeMax, 2.0D);
		float window = Math.max(0.2F, DoomsdayConfig.ticks(c.cameraShakeSeconds) / 20.0F);
		float decay = (float) MathUtil.decay(t / window, 1.6D);
		this.shake = (float) MathUtil.clamp(c.cameraShakeStrength, 0.0D, 3.0D) * (float) shakeAtten * decay;
		this.roll = (float) MathUtil.clamp(c.cameraShakeRollDegrees, 0.0D, 12.0D) * this.shake;
		// The FOV punch is a shockwave *arrival* effect, so it follows the same window but a
		// slower decay — the widening is what sells the over-pressure.
		this.fovPunch = c.fovPunchEnabled
			? (float) (MathUtil.clamp(c.flashFovDegrees, 70.0D, 160.0D) - 70.0D) * decay * (float) shakeAtten
			: 0.0F;
		this.shakeOffset[0] = (float) (Math.sin(t * 41.3D) * 0.5D + Math.sin(t * 17.7D) * 0.5D) * this.shake
			* (float) c.cameraShakeAmplitude;
		this.shakeOffset[1] = (float) (Math.sin(t * 33.1D + 1.7D) * 0.5D + Math.sin(t * 11.9D) * 0.5D)
			* this.shake * (float) c.cameraShakeAmplitude;
		this.shakeOffset[2] = (float) Math.sin(t * 23.7D + 0.9D) * this.shake * 0.4F
			* (float) c.cameraShakeAmplitude;

		// — atmosphere: fog/haze and sky darken as the cloud arrives, then recover
		double recover = Math.max(1.0D, c.atmosphereRecoverSeconds);
		float hold = (float) MathUtil.clamp(this.elapsed / (recover * 0.45D), 0.0D, 1.0D);
		float release = (float) MathUtil.decay(
			Math.max(0.0D, this.elapsed - recover * 0.45D) / recover, 2.4D);
		float atmosphere = (float) MathUtil.clamp(hold, 0.0D, 1.0D) * release;
		if (!c.atmosphericAftermath) {
			atmosphere = 0.0F;
		}
		this.hazeFog = atmosphere * (float) MathUtil.clamp(c.hazeDensity * 40.0D, 0.0D, 1.0D);
		this.skyDarkness = atmosphere * (float) MathUtil.clamp(c.skyDarkness, 0.0D, 0.95D);
		this.orangeMix = atmosphere * (float) MathUtil.clamp(c.fogOrangeMix, 0.0D, 1.0D);
		this.desaturation = Math.max(this.desaturation, atmosphere * 0.35F);
	}

	private void updateDistance() {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity player = client == null ? null : client.player;
		if (player == null) {
			this.distance = Double.MAX_VALUE;
			return;
		}
		this.distance = this.origin.distanceTo(player.getEyePos());
	}

	// ———————————————————————————————————————————————————— accessors

	public float flash() {
		return this.flash;
	}

	/** Hard whiteout — the HUD paints the whole screen this much white. */
	public float flashWhiteout() {
		return this.flashWhiteout;
	}

	public float desaturation() {
		return this.desaturation;
	}

	public float bloom() {
		return this.bloom;
	}

	public float chromatic() {
		return this.chromatic;
	}

	public float shake() {
		return this.shake;
	}

	public float rollDegrees() {
		return this.roll;
	}

	/** Degrees to *add* to the FOV this frame; 0 when the event is over. */
	public float fovPunch() {
		return this.fovPunch;
	}

	public float hazeFog() {
		return this.hazeFog;
	}

	public float skyDarkness() {
		return this.skyDarkness;
	}

	public float orangeMix() {
		return this.orangeMix;
	}

	/** Above this, the HUD paints lens dirt; keeps a quiet scene clean (config threshold). */
	public float lensDirtThreshold() {
		return this.lensDirt;
	}

	/** Screen-space shake offsets, read one float at a time (see GameRendererMixin). */
	public float shakeOffsetX() {
		return this.shakeOffset[0];
	}

	public float shakeOffsetY() {
		return this.shakeOffset[1];
	}

	public float shakeOffsetZ() {
		return this.shakeOffset[2];
	}

	public double distanceToEpicentre() {
		return this.distance;
	}

	public Vec3d origin() {
		return this.origin;
	}

	public double elapsed() {
		return this.elapsed;
	}

	public int eventSerial() {
		return this.eventSerial;
	}

	/**
	 * Re-anchor the atmosphere's long tail from the aftermath packet.
	 *
	 * <p>The detonation packet's own decay envelope is time-based, so a player who joined late, or
	 * a server whose recovery was slower than the client's estimate, would see the sky snap back to
	 * blue. This call restarts the recovery ramp from the server's authoritative numbers
	 * ({@code darkness} at the apex, {@code recoverSeconds} to clear), which is the only reason the
	 * aftermath packet exists at all.</p>
	 */
	public void noteAftermath(float darkness, float recoverSeconds) {
		if (darkness <= 0.001F) {
			return;
		}
		this.skyDarkness = Math.max(this.skyDarkness, darkness);
		this.hazeFog = Math.max(this.hazeFog, darkness * 0.55F);
		// The recovery window is stored as an absolute deadline measured in elapsed seconds, so a
		// tick() that runs twice (client reload) cannot extend it.
		this.aftermathUntil = this.elapsed + Math.max(1.0D, recoverSeconds);
	}

	private double aftermathUntil;

	/** Seconds of atmosphere recovery left, for the debug overlay. */
	public double aftermathRemaining() {
		return Math.max(0.0D, this.aftermathUntil - this.elapsed);
	}

	public boolean isActive() {
		return this.flash > 0.001F || this.shake > 0.001F || this.hazeFog > 0.001F;
	}

	/** Hard reset — used by the config screen's "stop" button and by {@code /doomsday config reset}. */
	public void clear() {
		this.aftermathUntil = 0.0D;
		this.flash = 0.0F;
		this.flashWhiteout = 0.0F;
		this.desaturation = 0.0F;
		this.bloom = 1.0F;
		this.chromatic = 0.0F;
		this.shake = 0.0F;
		this.roll = 0.0F;
		this.fovPunch = 0.0F;
		this.hazeFog = 0.0F;
		this.skyDarkness = 0.0F;
		this.orangeMix = 0.0F;
		this.elapsed = 1.0E7D;
		this.shakeOffset[0] = 0.0F;
		this.shakeOffset[1] = 0.0F;
		this.shakeOffset[2] = 0.0F;
	}
}
