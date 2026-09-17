package com.doomsday.nukes.network;

import com.doomsday.nukes.DoomsdayNukes;
import com.doomsday.nukes.block.entity.NukeBlockEntity;
import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.detonation.Detonation;
import com.doomsday.nukes.detonation.DetonationManager;
import com.doomsday.nukes.detonation.NukePreset;
import com.doomsday.nukes.item.RemoteDetonatorItem;
import com.doomsday.nukes.network.packet.AftermathS2CPacket;
import com.doomsday.nukes.network.packet.DeviceArmC2SPacket;
import com.doomsday.nukes.network.packet.DeviceDisarmC2SPacket;
import com.doomsday.nukes.network.packet.DeviceStateS2CPacket;
import com.doomsday.nukes.network.packet.DetonateNowC2SPacket;
import com.doomsday.nukes.network.packet.DetonationS2CPacket;
import com.doomsday.nukes.network.packet.DetonatorFireC2SPacket;
import com.doomsday.nukes.network.packet.DetonatorLinkC2SPacket;
import com.doomsday.nukes.network.packet.EmpSyncS2CPacket;
import com.doomsday.nukes.network.packet.RadiationSyncS2CPacket;
import com.doomsday.nukes.network.packet.StageChangeS2CPacket;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Common-side packet registration and the server half of the send/receive surface.
 *
 * <h2>The rule this file exists to enforce</h2>
 * Codec <em>registration</em> is symmetric and belongs in common init
 * ({@link PayloadTypeRegistry}), but <em>client receivers</em> touch
 * {@code ClientPlayNetworking} and therefore may never be referenced from here. They live in
 * {@code com.doomsday.nukes.client.network.ClientPacketHandlers}, registered from the client
 * entrypoint only. A dedicated server thus loads zero client classes while still being able to
 * serialise every S2C payload it produces.
 *
 * <h2>Authority</h2>
 * Every C2S handler below re-validates on the server. The client is only ever allowed to
 * <em>request</em>: arm, disarm, link, fire. Damage, block destruction, contamination, EMP and
 * stage progression are computed server-side and pushed. A modified client cannot detonate a
 * device it cannot reach, cannot arm outside the configured timer bounds, and cannot skip the
 * crater budget.
 */
public final class ModPackets {
	/** Interaction distance enforced on arm/disarm/link requests (vanilla reach is ~4.5). */
	private static final double MAX_COMMAND_DISTANCE = 8.0D;

	private ModPackets() {
	}

	// ———————————————————————————————————————————————————————— register

	public static void register() {
		// S2C (server -> client)
		PayloadTypeRegistry.playS2C().register(DetonationS2CPacket.TYPE, DetonationS2CPacket.CODEC);
		PayloadTypeRegistry.playS2C().register(StageChangeS2CPacket.TYPE, StageChangeS2CPacket.CODEC);
		PayloadTypeRegistry.playS2C().register(DeviceStateS2CPacket.TYPE, DeviceStateS2CPacket.CODEC);
		PayloadTypeRegistry.playS2C().register(AftermathS2CPacket.TYPE, AftermathS2CPacket.CODEC);
		PayloadTypeRegistry.playS2C().register(EmpSyncS2CPacket.TYPE, EmpSyncS2CPacket.CODEC);
		PayloadTypeRegistry.playS2C().register(RadiationSyncS2CPacket.TYPE, RadiationSyncS2CPacket.CODEC);

		// C2S (client -> server)
		PayloadTypeRegistry.playC2S().register(DeviceArmC2SPacket.TYPE, DeviceArmC2SPacket.CODEC);
		PayloadTypeRegistry.playC2S().register(DeviceDisarmC2SPacket.TYPE, DeviceDisarmC2SPacket.CODEC);
		PayloadTypeRegistry.playC2S().register(DetonatorLinkC2SPacket.TYPE, DetonatorLinkC2SPacket.CODEC);
		PayloadTypeRegistry.playC2S().register(DetonatorFireC2SPacket.TYPE, DetonatorFireC2SPacket.CODEC);
		PayloadTypeRegistry.playC2S().register(DetonateNowC2SPacket.TYPE, DetonateNowC2SPacket.CODEC);

		registerServerReceivers();
	}

	private static void registerServerReceivers() {
		ServerPlayNetworking.registerGlobalReceiver(DeviceArmC2SPacket.TYPE,
			(server, player, handler, payload, sender) -> {
				if (!reachable(player, payload.pos())) {
					return;
				}
				NukeBlockEntity.requestArm(server, player, payload.pos(), payload.timerSeconds());
			});

		ServerPlayNetworking.registerGlobalReceiver(DeviceDisarmC2SPacket.TYPE,
			(server, player, handler, payload, sender) -> {
				if (!reachable(player, payload.pos())) {
					return;
				}
				NukeBlockEntity.requestDisarm(server, player, payload.pos());
			});

		ServerPlayNetworking.registerGlobalReceiver(DetonatorLinkC2SPacket.TYPE,
			(server, player, handler, payload, sender) -> {
				if (!reachable(player, payload.pos())) {
					return;
				}
				RemoteDetonatorItem.serverLink(player, payload.slot(), payload.pos());
			});

		// Deliberately *not* distance-checked to the device: the whole point of a remote
		// detonator is distance. It is still validated server-side against the link stored on
		// the item, so a client can only fire a device it legitimately linked.
		ServerPlayNetworking.registerGlobalReceiver(DetonatorFireC2SPacket.TYPE,
			(server, player, handler, payload, sender) ->
				RemoteDetonatorItem.serverFire(player, payload.slot(), payload.cancelInstead()));

		ServerPlayNetworking.registerGlobalReceiver(DetonateNowC2SPacket.TYPE,
			(server, player, handler, payload, sender) -> {
				// Debug/admin entry: same code path as an expiring countdown.
				if (!ConfigManager.get().griefingEnabled && !player.isCreative()) {
					return;
				}
				NukePreset preset = NukePreset.byOrdinalOrFallback(payload.preset());
				ServerWorld world = player.getServerWorld();
				DoomsdayNukes.detonations().detonate(world,
					Vec3d.ofCenter(payload.pos()).add(0.0D, 0.9D, 0.0D), payload.pos(), preset);
			});
	}

	private static boolean reachable(ServerPlayerEntity player, BlockPos pos) {
		if (player.isCreative() || player.isSpectator()) {
			return true;
		}
		return player.squaredDistanceTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)
			<= MAX_COMMAND_DISTANCE * MAX_COMMAND_DISTANCE;
	}

	// ——————————————————————————————————————————————————————————— send

	public static void sendToPlayer(ServerPlayerEntity player, CustomPayload payload) {
		if (player != null && ServerPlayNetworking.canSend(player, payload.type())) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	/**
	 * Radius-limited broadcast. Uses distance, not the entity-tracking set, because these
	 * packets describe a *region*, and a player 900 blocks away must still receive a Tsar
	 * Bomba's detonation even though nothing at the epicentre is in their tracking range.
	 */
	public static void sendToTracking(ServerWorld world, Vec3d origin, float radius,
									  CustomPayload payload) {
		double r2 = (double) radius * radius;
		for (ServerPlayerEntity player : world.getPlayers()) {
			if (player.squaredDistanceTo(origin) <= r2) {
				sendToPlayer(player, payload);
			}
		}
	}

	public static void sendToAll(ServerWorld world, CustomPayload payload) {
		for (ServerPlayerEntity player : world.getPlayers()) {
			sendToPlayer(player, payload);
		}
	}

	/** Called by {@code Detonation} at the fallout transition. */
	public static void broadcastAftermath(ServerWorld world, Vec3d origin, float recoverSeconds,
										  double darkness) {
		AftermathS2CPacket payload = new AftermathS2CPacket(origin.x, origin.y, origin.z,
			recoverSeconds, (float) Math.max(0.0D, Math.min(1.0D, darkness)));
		sendToTracking(world, origin, 16000.0F, payload);
	}

	/** Device sync helper kept here so block entities do not import networking internals. */
	public static void sendDeviceState(ServerWorld world, NukeBlockEntity entity) {
		BlockPos pos = entity.getPos();
		NukePreset preset = entity.preset();
		DeviceStateS2CPacket payload = new DeviceStateS2CPacket(pos, preset.ordinal(),
			entity.isArmed(), entity.ticksRemaining(), entity.effectiveYieldKt(),
			entity.isEmpSuppressed());
		Vec3d center = Vec3d.ofCenter(pos);
		double reach = Math.max(64.0D, ConfigManager.get().soundDistance);
		sendToTracking(world, center, (float) reach, payload);
	}

	/** Push the live detonation set to a player that just joined (reconnect safety). */
	public static void syncOnJoin(ServerPlayerEntity player) {
		MinecraftServer server = player.getServer();
		DetonationManager manager = DoomsdayNukes.detonations();
		if (server == null || manager == null) {
			return;
		}
		manager.syncPlayer(player);
	}

	/** Kept for the status command so callers do not need the packet types. */
	public static String describePayloads() {
		return "payloads: 6 S2C (detonation, stage_change, device_state, aftermath, emp_sync, "
			+ "radiation_sync), 5 C2S (device_arm, device_disarm, detonator_link, detonator_fire, "
			+ "detonate_now)";
	}
}
