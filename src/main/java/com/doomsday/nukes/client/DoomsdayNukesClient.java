package com.doomsday.nukes.client;

import com.doomsday.nukes.DoomsdayNukes;
import com.doomsday.nukes.client.gui.NukeControlScreen;
import com.doomsday.nukes.client.hud.DoomsdayHud;
import com.doomsday.nukes.client.particle.AshPool;
import com.doomsday.nukes.client.render.VisualRenderers;
import com.doomsday.nukes.client.shader.PostPipelineBridge;
import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.entity.FalloutEntity;
import com.doomsday.nukes.effect.RadiationManager;
import com.doomsday.nukes.gui.ScreenOpener;
import com.doomsday.nukes.network.ClientPayloadSender;
import com.doomsday.nukes.network.packet.AftermathS2CPacket;
import com.doomsday.nukes.network.packet.DetonationS2CPacket;
import com.doomsday.nukes.network.packet.DeviceStateS2CPacket;
import com.doomsday.nukes.network.packet.EmpSyncS2CPacket;
import com.doomsday.nukes.network.packet.RadiationSyncS2CPacket;
import com.doomsday.nukes.network.packet.StageChangeS2CPacket;
import com.doomsday.nukes.util.MathUtil;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;
import com.doomsday.nukes.util.DText;

import java.util.ArrayList;
import java.util.List;

/**
 * Client entrypoint: the only place in this mod that is allowed to touch
 * {@code MinecraftClient}, {@code ClientPlayNetworking} or any render class.
 *
 * <h2>What it owns</h2>
 * <ol>
 *   <li><b>The two seams.</b> {@link ClientPayloadSender} and {@link ScreenOpener} are installed
 *       here, in that order and before anything else, because both are read by common code during
 *       the very first interaction. This is the single line of separation that makes the mod safe to
 *       install on a dedicated server: common classes never import a client class, they call through
 *       a slot that only this file fills.</li>
 *   <li><b>Client receivers.</b> Registered here rather than in {@code ModPackets}, because
 *       {@code ClientPlayNetworking} is client-only. Payload <em>types</em> and codecs stay common
 *       (both sides must agree on them); only the handlers are split.</li>
 *   <li><b>One client tick.</b> Renderer registration, HUD callback, and a single
 *       {@code END_CLIENT_TICK} that advances the clock, the state object, the Geiger driver, the
 *       visual pool, the particle budget and the stale-state sweep — in that order, because each
 *       step reads what the previous one wrote.</li>
 * </ol>
 *
 * <h2>Ordering inside the tick is load-bearing</h2>
 * {@code ClientTicks} first (everything timestamps against it), then
 * {@code ClientDetonationState.tick} (the flash/shake envelope the HUD and mixin read this frame),
 * then visuals (spawn/expire), then particles (they sample the fields the previous step just
 * updated), then the sweep (which must run last so it sees writes from all of the above).
 */
public final class DoomsdayNukesClient implements ClientModInitializer {
	private static long lastFrameNanos;
	private static double lastFrameSeconds = 0.05D;
	private static boolean pipelineOpen;

	@Override
	public void onInitializeClient() {
		// 1 — seams first: common code reads these during the very first click.
		ClientPayloadSender.install(ClientPlayNetworking::send);
		ScreenOpener.install(new ScreenOpener.Handler() {
			@Override
			public void openControlScreen(net.minecraft.util.math.BlockPos pos,
										  com.doomsday.nukes.detonation.NukePreset preset,
										  boolean armed, int ticksRemaining) {
				MinecraftClient client = MinecraftClient.getInstance();
				if (client != null) {
					client.setScreen(new NukeControlScreen(pos, preset, armed, ticksRemaining));
				}
			}

			@Override
			public void openConfigScreen() {
				MinecraftClient client = MinecraftClient.getInstance();
				if (client != null) {
					client.setScreen(new com.doomsday.nukes.client.gui.DoomsdayConfigScreen(null));
				}
			}
		});

		// 2 — renderers, then the HUD. Both are pure registration; neither reads config yet.
		VisualRenderers.register();
		HudRenderCallback.EVENT.register((context, tickCounter) ->
			DoomsdayHud.render(context, tickCounter.getTickDelta(true)));

		// 3 — client receivers. Every handler marshals onto the client thread with executeSync, so
		// none of them can touch a render structure from the network thread.
		ClientPlayNetworking.registerGlobalReceiver(DetonationS2CPacket.ID,
			(payload, receiver) -> receiver.client().execute(() -> DoomsdayVisuals.onDetonation(payload)));
		ClientPlayNetworking.registerGlobalReceiver(StageChangeS2CPacket.ID,
			(payload, receiver) -> receiver.client().execute(() -> onStageChange(payload)));
		ClientPlayNetworking.registerGlobalReceiver(DeviceStateS2CPacket.ID,
			(payload, receiver) -> receiver.client().execute(() -> onDeviceState(payload)));
		ClientPlayNetworking.registerGlobalReceiver(RadiationSyncS2CPacket.ID,
			(payload, receiver) -> receiver.client().execute(() ->
				RadiationManager.handleClientField(payload.chunkKeys(), payload.doses())));
		ClientPlayNetworking.registerGlobalReceiver(AftermathS2CPacket.ID,
			(payload, receiver) -> receiver.client().execute(() -> onAftermath(payload)));
		ClientPlayNetworking.registerGlobalReceiver(EmpSyncS2CPacket.ID,
			(payload, receiver) -> receiver.client().execute(() -> onEmpSync(payload)));

		// 4 — one client tick, in dependency order (see the class javadoc).
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			ClientTicks.advance();
			measureFrame();
			ClientDetonationState.get().tick(lastFrameSeconds);
			DoomsdayVisuals.tick();
			AshPool.tick(collectFalloutFields());
			ClientDeviceStates.sweep();
			GeigerDriver.tick();
			// The post-effect pipeline is a two-state switch, not a per-frame blend (see
			// PostPipelineBridge): on while the whiteout/desaturation window is open, off after.
			boolean hot = ClientDetonationState.get().flash() > 0.02F;
			if (hot && !pipelineOpen) {
				pipelineOpen = true;
				PostPipelineBridge.begin();
			} else if (!hot && pipelineOpen) {
				pipelineOpen = false;
				PostPipelineBridge.end();
			}
		});

		// 5 — lifecycle: a world change or a disconnect must not leave visuals behind, because the
		// entity ids they were given are meaningless in the next world.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetClientState());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			PostPipelineBridge.end();
			resetClientState();
		});
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			// A fresh world: re-probe the shader pipeline, since Iris may have been toggled.
			PostPipelineBridge.reprobe();
			if (ConfigManager.get().verboseLogging) {
				DoomsdayNukes.LOGGER.info("client ready — {}", PostPipelineBridge.describe());
			}
		});

		DoomsdayNukes.LOGGER.info("Doomsday Nukes client loaded (seams installed, {} renderers, "
			+ "{} payload receivers).", 5, 6);
	}

	// ———————————————————————————————————————————————————— packet handlers

	private static void onStageChange(StageChangeS2CPacket p) {
		com.doomsday.nukes.detonation.DetonationStage stage =
			com.doomsday.nukes.detonation.DetonationStage.byIndex(p.stage());
		if (stage == null || p.detonationId() < 0) {
			// A negative id is a /doomsday preview: no stage subtitle, no sound, no state change —
			// the visuals were already spawned by the preview path.
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.player == null) {
			return;
		}
		client.player.sendMessage(com.doomsday.nukes.util.DText.of(
			"subtitle.doomsday.stage." + stage.key()), true);
	}

	private static void onDeviceState(DeviceStateS2CPacket p) {
		if (!p.armed()) {
			ClientDeviceStates.remove(p.pos());
			return;
		}
		ClientDeviceStates.put(new ClientDeviceStates.State(p.pos(), p.preset(), p.armed(),
			p.ticksLeft(), p.yieldKt(), p.empSuppressed(), ClientTicks.get()));
	}

	private static void onAftermath(AftermathS2CPacket p) {
		// The aftermath packet is the atmosphere's long tail: it re-anchors the haze so the sky
		// keeps darkening even if the detonation packet arrived minutes ago and the state decayed.
		double distance = Double.MAX_VALUE;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null && client.player != null) {
			distance = new Vec3d(p.x(), p.y(), p.z()).distanceTo(client.player.getEyePos());
		}
		double atten = MathUtil.attenuation(distance, 64.0D,
			Math.max(256.0D, ConfigManager.get().cameraShakeMaxRadius), 2.0D);
		ClientDetonationState.get().noteAftermath((float) MathUtil.clamp(p.darkness() * atten,
			0.0D, 0.95D), Math.max(1.0F, p.recoverSeconds()));
	}

	private static void onEmpSync(EmpSyncS2CPacket p) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.player == null) {
			return;
		}
		// One action-bar line, no HUD state: the EMP's visible symptom is that the lamps and
		// screens around you went out, and a chat line is enough to explain why it happened. The
		// zone's extent is not sent, because the client cannot act on it — suppression is decided
		// entirely server-side when a trigger is pulled.
		client.player.sendMessage(DText.of(p.active()
			? "gui.doomsday.emp.hit" : "gui.doomsday.emp.recovered", p.ticks() / 20), true);
	}

	// ——————————————————————————————————————————————————————— plumbing

	private static List<FalloutEntity> collectFalloutFields() {
		MinecraftClient client = MinecraftClient.getInstance();
		List<FalloutEntity> out = new ArrayList<>(2);
		if (client == null || client.world == null) {
			return out;
		}
		// A box query rather than a world iteration: getEntitiesByClass walks only the chunk
		// sections inside the box, and the box is the player's own view distance, so the cost is
		// bounded by maxVisualEntities rather than by the world size.
		net.minecraft.entity.player.PlayerEntity player = client.player;
		double reach = Math.max(64.0D, ConfigManager.get().cloudVisibilityDistance);
		net.minecraft.util.math.Box box = new net.minecraft.util.math.Box(
			player.getX() - reach, player.getY() - reach, player.getZ() - reach,
			player.getX() + reach, player.getY() + reach, player.getZ() + reach);
		for (FalloutEntity fallout : client.world.getEntitiesByClass(FalloutEntity.class, box,
			e -> true)) {
			if (!fallout.isRemoved()) {
				out.add(fallout);
			}
		}
		return out;
	}

	private static void measureFrame() {
		long now = System.nanoTime();
		long previous = lastFrameNanos;
		lastFrameNanos = now;
		if (previous == 0L) {
			return;
		}
		// Clamped: a breakpoint or a loading screen must not fast-forward a 0.2 s whiteout, and a
		// 5 ms frame must not make the flash look twice as bright.
		lastFrameSeconds = MathUtil.clamp((now - previous) / 1.0E9D, 0.004D, 0.25D);
	}

	private static void resetClientState() {
		DoomsdayVisuals.clearAll();
		ClientDeviceStates.clear();
		AshPool.clear();
		GeigerDriver.reset();
		ClientTicks.reset();
		pipelineOpen = false;
	}

	/** Debug string for F3 and for {@code /doomsday status} when the client is the sender. */
	public static String describe() {
		return ClientDetonationState.get().isActive()
			? DoomsdayVisuals.describe() + ", " + AshPool.describe() + ", "
			+ PostPipelineBridge.describe()
			: "idle, " + PostPipelineBridge.describe();
	}
}
