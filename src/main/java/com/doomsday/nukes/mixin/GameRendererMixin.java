package com.doomsday.nukes.mixin;

import com.doomsday.nukes.client.ClientDetonationState;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The two camera hooks: the shockwave FOV punch and the impact shake. Kept to exactly two
 * injections in one target class, because every mixin is a compatibility liability the mod has to
 * carry forever.
 *
 * <h2>Why a mixin at all</h2>
 * There is no supported API for either effect. FOV is computed inside {@code GameRenderer} from the
 * player's sprint/scope/status state and handed straight to the projection matrix; the only other
 * lever is a movement-speed attribute, which is not an FOV at all. View offset has no API either.
 * So this is where they live — and only for these two things.
 *
 * <h2>Why the targets are safe</h2>
 * Both selectors were checked against the pinned mappings, not from memory:
 * {@code GameRenderer#getFov(Lnet/minecraft/client/render/Camera;FZ)F} and
 * {@code GameRenderer#bobView(Lnet/minecraft/client/util/math/MatrixStack;F)V}
 * (yarn 1.21.1+build.3 — see {@code gradle.properties}). Injection is at {@code TAIL} in both cases
 * and reads the value vanilla already computed, so an unrelated mod that also modifies FOV (sprint
 * indicators, dynamic FOV mods) keeps its change: this one adds on top instead of replacing.
 * That is the difference between "incompatible" and "both effects visible".
 *
 * <h2>Cost</h2>
 * {@link ClientDetonationState#fovPunch()} is one float read; both handlers return immediately when
 * it is zero (the normal case, i.e. every frame of every session without a detonation). No allocation,
 * no config lookup on the shake path beyond the one boolean check, no per-frame state.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	/**
	 * FOV punch. Vanilla returns an angle in degrees (70 by default), so this adds degrees —
	 * which is exactly how {@code flashFovDegrees} is documented in the config ("110 = a 40°
	 * widening at the peak").
	 */
	@Inject(method = "getFov", at = @At("TAIL"), cancellable = true)
	private void doomsday$fovPunch(Camera camera, float tickDelta, boolean changingFov,
								   CallbackInfoReturnable<Float> cir) {
		ClientDetonationState state = ClientDetonationState.get();
		float punch = state.fovPunch();
		if (punch <= 0.05F) {
			return;
		}
		// The punch is scaled by how far the user's own FOV setting is from the vanilla 70, so a
		// player on FOV 110 does not get a *narrowing* from our "110 degrees" peak.
		float base = cir.getReturnValueF();
		float relative = base / 70.0F;
		float widened = base + punch * Math.max(0.35F, relative);
		cir.setReturnValue(MathHelper.clamp(widened, 1.0F, 179.0F));
	}

	/**
	 * Camera shake, applied inside {@code bobView} — the method vanilla itself uses to rock the view
	 * while walking. Adding to that matrix rather than writing a new one means the shake composes
	 * with walking bob, the hurt tilt and any other mod's view transform instead of erasing them.
	 */
	@Inject(method = "bobView", at = @At("TAIL"))
	private void doomsday$shake(MatrixStack matrices, float tickDelta, CallbackInfo ci) {
		ClientDetonationState state = ClientDetonationState.get();
		// No separate "shake enabled" flag: {@code cameraShakeStrength = 0} already makes
		// state.shake() zero for every frame, so one comparison here covers both.
		if (state.shake() <= 0.002F) {
			return;
		}
		// Three float getters rather than a float[] out-param: an array allocation per frame in the
		// view transform is exactly the kind of garbage that shows up as GC stutter mid-animation.
		matrices.translate(state.shakeOffsetX() * 0.35D, state.shakeOffsetY() * 0.35D, 0.0D);
		float roll = state.rollDegrees();
		if (Math.abs(roll) > 0.01F) {
			matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(roll));
		}
	}
}
