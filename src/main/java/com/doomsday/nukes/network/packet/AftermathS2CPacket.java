package com.doomsday.nukes.network.packet;

import com.doomsday.nukes.DoomsdayNukes;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/**
 * Atmospheric aftermath: how dark the sky goes and how long recovery takes.
 *
 * <p>One packet per detonation drives a multi-minute sky/fog change. Clients interpolate from
 * their own render clock; nothing is sent per tick.</p>
 */
public record AftermathS2CPacket(
			double x,
			double y,
			double z,
			float recoverSeconds,
			float darkness
) implements CustomPayload {
	public static final CustomPayload.Id<AftermathS2CPacket> ID =
		new CustomPayload.Id<>(DoomsdayNukes.id("aftermath"));

	public static final PacketCodec<RegistryByteBuf, AftermathS2CPacket> CODEC =
		CustomPayload.codecOf((buf, payload) -> payload.writePayload(buf),
			AftermathS2CPacket::read);

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
	}

	private void writePayload(RegistryByteBuf buf) {
		buf.writeDouble(x);
		buf.writeDouble(y);
		buf.writeDouble(z);
		buf.writeFloat(recoverSeconds);
		buf.writeFloat(darkness);
	}

	private static AftermathS2CPacket read(RegistryByteBuf buf) {
		double x = buf.readDouble();
		double y = buf.readDouble();
		double z = buf.readDouble();
		float recoverSeconds = buf.readFloat();
		float darkness = buf.readFloat();
		return new AftermathS2CPacket(x, y, z, recoverSeconds, darkness);
	}
}
