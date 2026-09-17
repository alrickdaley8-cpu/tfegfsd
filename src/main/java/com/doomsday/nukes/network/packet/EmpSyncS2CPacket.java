package com.doomsday.nukes.network.packet;

import com.doomsday.nukes.DoomsdayNukes;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/**
 * Marks an EMP region on the client for the HUD indicator and the device 'deaf' state.
 *
 * <p>Block-level EMP visuals (flickering lamps, dead torches) deliberately do <em>not</em> use
 * this packet — they ride the normal vanilla block-update channel, which is cheaper and
 * guaranteed to agree with the server.</p>
 */
public record EmpSyncS2CPacket(
			double x,
			double y,
			double z,
			int ticks,
			boolean active
) implements CustomPayload {
	public static final CustomPayload.Type<EmpSyncS2CPacket> TYPE =
		new CustomPayload.Type<>(DoomsdayNukes.id("emp_sync"));

	public static final PacketCodec<RegistryByteBuf, EmpSyncS2CPacket> CODEC =
		PacketCodec.uniform(EmpSyncS2CPacket::writePayload, EmpSyncS2CPacket::read);

	@Override
	public CustomPayload.Type<? extends CustomPayload> type() {
		return TYPE;
	}

	private void writePayload(RegistryByteBuf buf) {
		buf.writeDouble(x);
		buf.writeDouble(y);
		buf.writeDouble(z);
		buf.writeVarInt(ticks);
		buf.writeBoolean(active);
	}

	private static EmpSyncS2CPacket read(RegistryByteBuf buf) {
		double x = buf.readDouble();
		double y = buf.readDouble();
		double z = buf.readDouble();
		int ticks = buf.readVarInt();
		boolean active = buf.readBoolean();
		return new EmpSyncS2CPacket(x, y, z, ticks, active);
	}
}
