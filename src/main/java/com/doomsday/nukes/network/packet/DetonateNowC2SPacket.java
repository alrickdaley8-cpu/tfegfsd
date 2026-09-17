package com.doomsday.nukes.network.packet;

import com.doomsday.nukes.DoomsdayNukes;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.BlockPos;

/**
 * Admin/debug trigger for {@code /doomsday detonate}.
 *
 * <p>Goes through the exact same server path as a countdown expiring, so there is no second,
 * less-tested detonation route.</p>
 */
public record DetonateNowC2SPacket(
			BlockPos pos,
			int preset
) implements CustomPayload {
	public static final CustomPayload.Type<DetonateNowC2SPacket> TYPE =
		new CustomPayload.Type<>(DoomsdayNukes.id("detonate_now"));

	public static final PacketCodec<RegistryByteBuf, DetonateNowC2SPacket> CODEC =
		PacketCodec.uniform(DetonateNowC2SPacket::writePayload, DetonateNowC2SPacket::read);

	@Override
	public CustomPayload.Type<? extends CustomPayload> type() {
		return TYPE;
	}

	private void writePayload(RegistryByteBuf buf) {
		buf.writeBlockPos(pos);
		buf.writeVarInt(preset);
	}

	private static DetonateNowC2SPacket read(RegistryByteBuf buf) {
		BlockPos pos = buf.readBlockPos();
		int preset = buf.readVarInt();
		return new DetonateNowC2SPacket(pos, preset);
	}
}
