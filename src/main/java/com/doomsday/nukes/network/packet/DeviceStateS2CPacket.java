package com.doomsday.nukes.network.packet;

import com.doomsday.nukes.DoomsdayNukes;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.BlockPos;

/**
 * Authoritative device state: countdown display, warning light, fins, EMP deafness.
 *
 * <p>Sent when a device enters a player's view, on arm/disarm, and once per second of a live
 * countdown. In between, clients count down from the shared world clock, so an armed device
 * costs no per-tick bandwidth.</p>
 */
public record DeviceStateS2CPacket(
			BlockPos pos,
			int preset,
			boolean armed,
			int ticksLeft,
			double yieldKt,
			boolean empSuppressed
) implements CustomPayload {
	public static final CustomPayload.Type<DeviceStateS2CPacket> TYPE =
		new CustomPayload.Type<>(DoomsdayNukes.id("device_state"));

	public static final PacketCodec<RegistryByteBuf, DeviceStateS2CPacket> CODEC =
		PacketCodec.uniform(DeviceStateS2CPacket::writePayload, DeviceStateS2CPacket::read);

	@Override
	public CustomPayload.Type<? extends CustomPayload> type() {
		return TYPE;
	}

	private void writePayload(RegistryByteBuf buf) {
		buf.writeBlockPos(pos);
		buf.writeVarInt(preset);
		buf.writeBoolean(armed);
		buf.writeVarInt(ticksLeft);
		buf.writeDouble(yieldKt);
		buf.writeBoolean(empSuppressed);
	}

	private static DeviceStateS2CPacket read(RegistryByteBuf buf) {
		BlockPos pos = buf.readBlockPos();
		int preset = buf.readVarInt();
		boolean armed = buf.readBoolean();
		int ticksLeft = buf.readVarInt();
		double yieldKt = buf.readDouble();
		boolean empSuppressed = buf.readBoolean();
		return new DeviceStateS2CPacket(pos, preset, armed, ticksLeft, yieldKt, empSuppressed);
	}
}
