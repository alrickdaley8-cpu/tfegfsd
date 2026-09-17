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
	public static final CustomPayload.Id<EmpSyncS2CPacket> ID =
		new CustomPayload.Id<>(DoomsdayNukes.id("emp_sync"));

	public static final PacketCodec<RegistryByteBuf, EmpSyncS2CPacket> CODEC =
		CustomPayload.codecOf(EmpSyncS2CPacket::writePayload,
			EmpSyncS2CPacket::read);

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
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
