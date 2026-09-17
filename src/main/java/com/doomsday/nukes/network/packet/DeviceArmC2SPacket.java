package com.doomsday.nukes.network.packet;

import com.doomsday.nukes.DoomsdayNukes;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.BlockPos;

/**
 * Client asks the server to arm a device, or re-set the timer of a live one. Stage 0 entry point.
 *
 * <p>The server re-validates everything: a device must exist at {@code pos}, the timer must be
 * inside {@code [minTimerSeconds, maxTimerSeconds]}, the player must be within interaction
 * distance, and an active EMP suppresses arming. The client is never trusted with an arm
 * decision, because an armed device is a world-mutating thing.</p>
 */
public record DeviceArmC2SPacket(
			BlockPos pos,
			int timerSeconds
) implements CustomPayload {
	public static final CustomPayload.Id<DeviceArmC2SPacket> ID =
		new CustomPayload.Id<>(DoomsdayNukes.id("device_arm"));

	public static final PacketCodec<RegistryByteBuf, DeviceArmC2SPacket> CODEC =
		CustomPayload.codecOf((buf, payload) -> payload.writePayload(buf),
			DeviceArmC2SPacket::read);

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
	}

	private void writePayload(RegistryByteBuf buf) {
		buf.writeBlockPos(pos);
		buf.writeVarInt(timerSeconds);
	}

	private static DeviceArmC2SPacket read(RegistryByteBuf buf) {
		BlockPos pos = buf.readBlockPos();
		int timerSeconds = buf.readVarInt();
		return new DeviceArmC2SPacket(pos, timerSeconds);
	}
}
