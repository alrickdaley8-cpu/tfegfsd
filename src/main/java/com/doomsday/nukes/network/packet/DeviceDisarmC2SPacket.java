package com.doomsday.nukes.network.packet;

import com.doomsday.nukes.DoomsdayNukes;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.BlockPos;

/**
 * Client asks the server to disarm a live device.
 *
 * <p>Honoured only when {@code allowDisarm} is enabled. The server always answers with the
 * resulting device state, so the client UI cannot drift when a request is refused.</p>
 */
public record DeviceDisarmC2SPacket(
			BlockPos pos
) implements CustomPayload {
	public static final CustomPayload.Id<DeviceDisarmC2SPacket> ID =
		new CustomPayload.Id<>(DoomsdayNukes.id("device_disarm"));

	public static final PacketCodec<RegistryByteBuf, DeviceDisarmC2SPacket> CODEC =
		CustomPayload.codecOf(DeviceDisarmC2SPacket::writePayload,
			DeviceDisarmC2SPacket::read);

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
	}

	private void writePayload(RegistryByteBuf buf) {
		buf.writeBlockPos(pos);
	}

	private static DeviceDisarmC2SPacket read(RegistryByteBuf buf) {
		BlockPos pos = buf.readBlockPos();
		return new DeviceDisarmC2SPacket(pos);
	}
}
