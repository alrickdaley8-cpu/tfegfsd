package com.doomsday.nukes.client.render;

import com.doomsday.nukes.DoomsdayNukes;
import com.doomsday.nukes.client.ClientDetonationState;
import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.config.DoomsdayConfig;
import com.doomsday.nukes.entity.CloudAnchorEntity;
import com.doomsday.nukes.entity.FalloutEntity;
import com.doomsday.nukes.entity.FireballEntity;
import com.doomsday.nukes.entity.MushroomCloudEntity;
import com.doomsday.nukes.entity.ShockwaveEntity;
import com.doomsday.nukes.registry.ModEntities;
import com.doomsday.nukes.util.MathUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Vector3f;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

/**
 * The five visual renderers, in one file, on purpose.
 *
 * <h2>Why one file</h2>
 * All five do the same three things: get the camera orientation, push coloured vertices into a
 * translucent layer, and decide how many of them to push at this distance. Splitting them into five
 * classes means five copies of that math — and the copy that diverges is always the one that only
 * breaks at 3 km, at night, in the rain. One file, one {@link Quads} helper, five small shape
 * functions.
 *
 * <h2>Why immediate-mode vertices and no model</h2>
 * A {@code EntityModel}/GeckoLib cloud would mean 100+ animated bones per frame on the CPU plus an
 * asset pipeline, for a shape that is fundamentally "N round puffs at computed positions". Quads
 * cost one draw call per layer, scale with the quality tier by changing a <em>count</em> instead of
 * swapping an asset, and are resolution independent: a 16×16 puff texture looks right at 4 blocks
 * wide and at 400.
 *
 * <h2>Distance handling</h2>
 * Past {@code cloudLodNearDistance} the cloud drops from per-puff underside shading to single
 * puffs; past {@code cloudLodFarDistance} it becomes a dozen puffs total. LOD is therefore a sample
 * count chosen from numbers the entity already carries — never a shader permutation, which is what
 * makes it survive Sodium, Iris and OptiFine-style renderers without a compat layer.
 *
 * <h2>Coordinate space</h2>
 * {@code EntityRendererDispatcher} has already translated the {@link MatrixStack} to
 * camera-relative space and to the entity's position before {@code render} is called, so every
 * vertex here is written relative to the epicentre. That is also why the entities can be pinned to a
 * single point (see {@code VisualEffectEntity}) and the shapes can still be kilometres wide.
 */
public final class VisualRenderers {
	public static final Identifier GLOW = DoomsdayNukes.id("textures/entity/glow.png");
	public static final Identifier PUFF = DoomsdayNukes.id("textures/entity/cloud_puff.png");
	public static final Identifier ASH = DoomsdayNukes.id("textures/entity/ash_puff.png");
	/** Fullbright: the light value every visual uses, since they are self-luminous or silhouettes. */
	private static final int FULL_LIGHT = 0xF000F0;
	private static final int SHOCKWAVE_SEGMENTS = 48;

	private VisualRenderers() {
	}

	// ———————————————————————————————————————————————————— registration

	/** Called once from the client entrypoint, in the same order as {@link ModEntities}. */
	public static void register() {
		net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(
			ModEntities.FIREBALL, Fireball::new);
		net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(
			ModEntities.SHOCKWAVE, Shockwave::new);
		net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(
			ModEntities.MUSHROOM_CLOUD, Cloud::new);
		net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(
			ModEntities.FALLOUT, Fallout::new);
		net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(
			ModEntities.CLOUD_ANCHOR, Anchor::new);
	}

	// ———————————————————————————————————————————————————————— helpers

	/** Camera-facing quad emitter bound to one matrix pose and one vertex buffer. */
	private static final class Quads {
		private final MatrixStack.Entry pose;
		private final VertexConsumer vertex;

		Quads(MatrixStack stack, VertexConsumer vertex) {
			this.pose = stack.peek();
			this.vertex = vertex;
		}

		/** A quad centred on (x, y, z) in the current — usually billboarded — space. */
		void center(float x, float y, float z, float halfWidth, float halfHeight,
					int r, int g, int b, int a) {
			quad(x - halfWidth, y - halfHeight, z, x + halfWidth, y + halfHeight, r, g, b, a);
		}

		void quad(float x0, float y0, float z, float x1, float y1,
				  int r, int g, int b, int a) {
			vertex(x0, y0, z, 0.0F, 1.0F, r, g, b, a);
			vertex(x1, y0, z, 1.0F, 1.0F, r, g, b, a);
			vertex(x1, y1, z, 1.0F, 0.0F, r, g, b, a);
			vertex(x0, y1, z, 0.0F, 0.0F, r, g, b, a);
		}

		private void vertex(float x, float y, float z, float u, float v,
							 int r, int g, int b, int a) {
			emit(vertex, pose, x, y, z, r, g, b, a, u, v, FULL_LIGHT, 0.0F, 0.0F, 1.0F);
		}

		/**
		 * One call per vertex. 1.21.1's VertexConsumer has no {@code next()} any more: the bulk
		 * {@code vertex(x, y, z, colour, u, v, overlay, light, nx, ny, nz)} writes every element of
		 * the current vertex and then starts the next one — which is also why the pose has to be
		 * applied here, since the bulk form is handed model-space numbers rather than a matrix.
		 *
		 * <p>Doing it in one call also sidesteps the element-order rule of the fluent setters (the
		 * consumer insists they arrive in the order its {@link RenderLayer}'s format declares), so
		 * a layer swap cannot turn a render pass into an {@code IllegalStateException}.</p>
		 */
		static void emit(VertexConsumer out, MatrixStack.Entry pose, float x, float y, float z,
				int r, int g, int b, int a, float u, float v, int light,
				float nx, float ny, float nz) {
			Vector3f p = new Vector3f(x, y, z);
			pose.getPositionMatrix().transformPosition(p);
			Vector3f n = new Vector3f(nx, ny, nz);
			pose.getNormalMatrix().transform(n);
			out.vertex(p.x(), p.y(), p.z(), (a << 24) | (r << 16) | (g << 8) | b, u, v,
				OverlayTexture.DEFAULT_UV, light, n.x(), n.y(), n.z());
		}
	}

	/** Rotate the stack so the following quads face the camera (standard billboard setup). */
	private static void faceCamera(MatrixStack stack, Camera camera) {
		stack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-camera.getYaw()));
		stack.multiply(RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
	}

	private static Camera camera() {
		MinecraftClient client = MinecraftClient.getInstance();
		return client != null && client.gameRenderer != null ? client.gameRenderer.getCamera() : null;
	}

	private static float distanceToCamera(Camera cam, Vec3d world) {
		if (cam == null) {
			return 0.0F;
		}
		return (float) cam.getPos().distanceTo(world);
	}

	private static int alpha255(float v) {
		return (int) (255.0F * MathHelper.clamp(v, 0.0F, 1.0F));
	}

	/** Shared plumbing: camera access, texture contract, and "always a candidate for rendering". */
	private abstract static class Visual<T extends Entity> extends EntityRenderer<T> {
		Visual(EntityRendererFactory.Context ctx) {
			super(ctx);
		}

		@Override
		public Identifier getTexture(T entity) {
			// The layers chosen in render() carry their own textures; this satisfies the abstract
			// contract (and any code path that asks) with a texture this mod ships.
			return PUFF;
		}

		@Override
		public boolean shouldRender(T entity, net.minecraft.client.render.Frustum frustum,
				double cameraX, double cameraY, double cameraZ) {
			// These entities are pinned to the epicentre and can be kilometres wide, so the
			// per-entity frustum/distance test is both wrong (a 400-block cloud has a 1-block box)
			// and unnecessary (DoomsdayVisuals never creates them past the configured view
			// distance, and each renderer fades itself out by distance).
			return true;
		}

	}

	// ——————————————————————————————————————————————————————— fireball

	public static final class Fireball extends Visual<FireballEntity> {
		public Fireball(EntityRendererFactory.Context ctx) {
			super(ctx);
		}

		@Override
		public void render(FireballEntity entity, float yaw, float tickDelta, MatrixStack stack,
						   VertexConsumerProvider consumers, int light) {
			double radius = entity.currentRadius();
			if (radius < 0.05D) {
				return;
			}
			DoomsdayConfig c = ConfigManager.get();
			float alpha = (float) entity.intensity();
			float[] rgb = MathUtil.blackbody(entity.temperature());
			float r = (float) radius;
			int cr = (int) (255.0F * Math.min(1.0F, rgb[0] * 1.6F));
			int cg = (int) (255.0F * Math.min(1.0F, rgb[1] * 1.15F));
			int cb = (int) (255.0F * Math.min(1.0F, rgb[2] * 1.05F));
			int a = alpha255(alpha);
			stack.push();
			VertexConsumer vertex = consumers.getBuffer(RenderLayer.getBeaconBeam(GLOW, true));
			Quads quads = new Quads(stack, vertex);
			// Three crossed quads read as a sphere from any angle at this size, and the fireball is
			// blown out by the flash in the same frame anyway: a real icosphere would cost ~20×
			// the vertices for a silhouette nobody can measure.
			quads.center(0.0F, 0.0F, 0.0F, r, r, cr, cg, cb, a);
			quads.center(0.0F, 0.0F, 0.0F, r * 0.2F, r, cr, cg, cb, a);
			if (c.heatHaze) {
				// Inner core, slightly offset by a frame-varying term: the "boiling" edge without a
				// noise texture or a shader.
				float wobble = (float) Math.sin(entity.age * 0.37D) * r * 0.06F;
				float inner = r * 0.62F;
				quads.center(wobble, -wobble * 0.4F, 0.02F, inner, inner,
					255, 250, 235, Math.min(255, a + 40));
			}
			stack.pop();
		}
	}

	// ————————————————————————————————————————————————————— shockwave

	public static final class Shockwave extends Visual<ShockwaveEntity> {
		public Shockwave(EntityRendererFactory.Context ctx) {
			super(ctx);
		}

		@Override
		public void render(ShockwaveEntity entity, float yaw, float tickDelta, MatrixStack stack,
						   VertexConsumerProvider consumers, int light) {
			double front = entity.frontRadius();
			float alpha = (float) entity.alpha();
			if (front < 0.5D || alpha <= 0.01F) {
				return;
			}
			DoomsdayConfig c = ConfigManager.get();
			int rings = MathHelper.clamp(c.shockwaveRingCount, 1, 6);
			float inner = (float) entity.innerRadius();
			float outer = (float) front;
			float height = (float) (6.0D + (outer - inner) * 0.55D);
			stack.push();
			MatrixStack.Entry pose = stack.peek();
			VertexConsumer vertex = consumers.getBuffer(RenderLayer.getEntityTranslucent(GLOW));
			// The wall is a vertical band between inner and outer radius, one quad per segment. A
			// ring of 48 segments is the cheapest shape that still reads as a *front* rather than a
			// torus, and it holds at 300 blocks/s because the segment count is not tied to speed.
			for (int ring = 0; ring < rings; ring++) {
				float shrink = 1.0F - ring * 0.16F;
				float i = inner * shrink;
				float o = outer * (1.0F + ring * 0.1F);
				int ringAlpha = (int) (200.0F * alpha * (1.0F - ring / (float) Math.max(1, rings)));
				if (ringAlpha <= 2) {
					continue;
				}
				for (int s = 0; s < SHOCKWAVE_SEGMENTS; s++) {
					double a0 = s / (double) SHOCKWAVE_SEGMENTS * Math.PI * 2.0D;
					double a1 = (s + 1.0D) / SHOCKWAVE_SEGMENTS * Math.PI * 2.0D;
					float x0 = (float) Math.cos(a0);
					float z0 = (float) Math.sin(a0);
					float x1 = (float) Math.cos(a1);
					float z1 = (float) Math.sin(a1);
					// Bottom edge on the inner radius, top edge on the outer: the slant is what
					// makes the band read as a wall leaning away from the blast.
					Quads.emit(vertex, pose, x0 * i, -height, z0 * i, 226, 238, 250, ringAlpha,
						0.0F, 1.0F, light, x0, 0.0F, z0);
					Quads.emit(vertex, pose, x1 * i, -height, z1 * i, 226, 238, 250, ringAlpha,
						1.0F, 1.0F, light, x1, 0.0F, z1);
					Quads.emit(vertex, pose, x1 * o, 0.0F, z1 * o, 255, 255, 255,
						ringAlpha / 2, 1.0F, 0.0F, light, x1, 0.0F, z1);
					Quads.emit(vertex, pose, x0 * o, 0.0F, z0 * o, 255, 255, 255,
						ringAlpha / 2, 0.0F, 0.0F, light, x0, 0.0F, z0);
				}
			}
			stack.pop();
		}
	}

	// ———————————————————————————————————————————————————————— cloud

	public static final class Cloud extends Visual<MushroomCloudEntity> {
		Cloud(EntityRendererFactory.Context ctx) {
			super(ctx);
		}

		@Override
		public void render(MushroomCloudEntity entity, float yaw, float tickDelta,
						   MatrixStack stack, VertexConsumerProvider consumers, int light) {
			float opacity = (float) entity.opacity();
			if (opacity <= 0.01F) {
				return;
			}
			Camera cam = camera();
			double distance = cam == null ? 0.0D : distanceToCamera(cam, entity.origin());
			boolean far = distance > entity.lodNear();
			boolean veryFar = distance > entity.lodFar();
			int capPuffs = veryFar ? 6 : (far ? Math.max(8, entity.capSamples() / 3)
				: entity.capSamples());
			int stemPuffs = veryFar ? 2 : (far ? Math.max(3, entity.stemSegments() / 2)
				: entity.stemSegments());
			int skirtPuffs = veryFar ? 4 : entity.skirtSamples();

			ClientDetonationState state = ClientDetonationState.get();
			float heat = MathHelper.clamp(state.flash() * 0.8F, 0.0F, 1.0F);
			float capR = (float) entity.capRadius();
			double rise = entity.riseHeight();
			// Colour: lit from the flash while the event is hot, ash-grey afterwards. Lerp is
			// written out rather than using MathHelper's overload, whose argument order moved.
			int cr = (int) (138.0F + (250.0F - 138.0F) * heat);
			int cg = (int) (132.0F + (228.0F - 132.0F) * heat);
			int cb = (int) (126.0F + (205.0F - 126.0F) * heat);

			stack.push();
			if (cam != null) {
				faceCamera(stack, cam);
			}
			Quads quads = new Quads(stack, consumers.getBuffer(RenderLayer.getEntityTranslucent(PUFF)));
			// — stem
			for (int i = 0; i < stemPuffs; i++) {
				float t = i / (float) Math.max(1, stemPuffs - 1);
				float y = (float) (rise * t) - 6.0F + (float) Math.sin(t * 3.0F) * 2.0F;
				float sr = (float) entity.stemRadius(t);
				double spin = t * 2.2D + entity.progress() * 0.6D;
				float wobble = (float) entity.boil(i, spin);
				quads.center(wobble, y, -wobble * 0.5F, sr * 1.5F, sr,
					(int) (cr * 0.86F), (int) (cg * 0.82F), (int) (cb * 0.78F),
					(int) (150.0F * opacity));
			}
			// — cap: puffs on a spiral, which distributes better than a grid and needs no index
			// buffer to describe.
			for (int i = 0; i < capPuffs; i++) {
				double t = capPuffs <= 1 ? 0.5D : i / (double) (capPuffs - 1);
				double angle = t * Math.PI * 6.0D;
				double rr = capR * Math.sqrt(Math.max(0.0D, 1.0D - (t - 0.5D) * (t - 0.5D) * 1.4D));
				float x = (float) (Math.cos(angle) * rr);
				float z = (float) (Math.sin(angle) * rr * 0.62D);
				float y = (float) (rise + Math.sin(t * Math.PI) * capR * 0.42D
					+ entity.boil(i + 977, t) * 0.5D);
				float size = capR * (0.30F + 0.12F * (float) Math.sin(t * Math.PI));
				int shade = (int) (cr * (0.78F + 0.30F * (float) t));
				quads.center(x, y, z, size, size * 0.72F, shade, (int) (cg * 0.96F),
					(int) (cb * 0.92F), (int) (168.0F * opacity));
				if (!veryFar) {
					// Near field: a darker puff under each one gives the underside volume. Past the
					// far LOD that shading is sub-pixel, which is exactly when to stop paying for it.
					quads.center(x * 0.9F, y - size * 0.5F, z, size * 0.8F, size * 0.5F,
						(int) (cr * 0.55F), (int) (cg * 0.52F), (int) (cb * 0.5F),
						(int) (120.0F * opacity));
				}
			}
			// — skirt: the curling-back rim.
			for (int i = 0; i < skirtPuffs; i++) {
				double angle = i / (double) Math.max(1, skirtPuffs) * Math.PI * 2.0D;
				double rr = entity.skirtRadius();
				float x = (float) (Math.cos(angle) * rr);
				float z = (float) (Math.sin(angle) * rr * 0.7D);
				float drop = (float) (capR * 0.35D
					* (0.4D + 0.6D * Math.abs(Math.sin(angle * 2.0D))));
				quads.center(x, (float) rise - drop, z, (float) (capR * 0.28D),
					(float) (capR * 0.2D), (int) (cr * 0.62F), (int) (cg * 0.6F),
					(int) (cb * 0.58F), (int) (130.0F * opacity));
			}
			stack.pop();
		}
	}

	// ——————————————————————————————————————————————————— fallout marker

	/**
	 * The fallout field's own geometry: one haze ring hugging the ground.
	 *
	 * <p>The ash itself is particles from {@code AshPool}; this renderer draws the one piece of
	 * information particles cannot convey — <em>where the boundary is</em>, when you are standing
	 * near it. It is deliberately faint (alpha ≤ 26/255): it is a measurement aid, not a paint
	 * layer, and it disappears entirely at LOW quality.</p>
	 */
	public static final class Fallout extends Visual<FalloutEntity> {
		Fallout(EntityRendererFactory.Context ctx) {
			super(ctx);
		}

		@Override
		public void render(FalloutEntity entity, float yaw, float tickDelta, MatrixStack stack,
						   VertexConsumerProvider consumers, int light) {
			DoomsdayConfig c = ConfigManager.get();
			if (c.quality == DoomsdayConfig.Quality.LOW) {
				return;
			}
			float radius = (float) entity.currentRadius();
			float a = (float) entity.density();
			if (radius < 4.0F || a <= 0.02F) {
				return;
			}
			stack.push();
			// -90° about X turns a billboard into a horizontal quad on the ground plane.
			stack.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90.0F));
			new Quads(stack, consumers.getBuffer(RenderLayer.getEntityTranslucent(ASH)))
				.center(0.0F, 0.0F, 0.0F, radius, radius, 150, 120, 90, (int) (26.0F * a));
			stack.pop();
		}
	}

	/**
	 * The cloud's ground shadow, as one dark horizontal ellipse.
	 *
	 * <p>World-space rather than screen-space on purpose: a world quad is trivially correct at any
	 * GUI scale, occludes with the terrain for free, and costs one quad. A screen-space projection of
	 * a 3 km ellipse would need the depth buffer to be read back — which is a frame stall.</p>
	 */
	public static final class Anchor extends Visual<CloudAnchorEntity> {
		Anchor(EntityRendererFactory.Context ctx) {
			super(ctx);
		}

		@Override
		public void render(CloudAnchorEntity entity, float yaw, float tickDelta, MatrixStack stack,
						   VertexConsumerProvider consumers, int light) {
			if (!entity.shadowEnabled()) {
				return;
			}
			double darkness = entity.darkness();
			if (darkness <= 0.005D) {
				return;
			}
			float radius = (float) entity.shadowRadius();
			stack.push();
			stack.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90.0F));
			int alpha = (int) (110.0F * MathHelper.clamp((float) darkness, 0.0F, 1.0F));
			new Quads(stack, consumers.getBuffer(RenderLayer.getTranslucent()))
				.center(0.0F, 0.0F, 0.0F, radius, radius * 0.86F, 8, 8, 10, alpha);
			stack.pop();
		}
	}
}
