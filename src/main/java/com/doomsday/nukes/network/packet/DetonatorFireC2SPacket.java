package com.doomsday.nukes.network.packet;

import com.doomsday.nukes.DoomsdayNukes;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/**
 * Pull the trigger on the linked device.
 *
 * <p>With {@code cancelInstead} the server performs a disarm instead: that is the
 * 'optionally cancel or re-arm' behaviour of the detonator, and it is a server decision, not a
 * client one.</p>
 */
public record DetonatorFireC2SPacket(
			int slot,
			boolean cancelInstead
) implements CustomPayload {
	public static final CustomPayload.Type<DetonatorFireC2SPacket> TYPE =
		new CustomPayload.Type<>(DoomsdayNukes.id("detonator_fire"));

	public static final PacketCodec<RegistryByteBuf, DetonatorFireC2SPacket> CODEC =
		PacketCodec.uniform(DetonatorFireC2SPacket::writePayload, DetonatorFireC2SPacket::read);

	@Override
	public CustomPayload.Type<? extends CustomPayload> type() {
		return TYPE;
	}

	private void writePayload(RegistryByteBuf buf) {
		buf.writeVarInt(slot);
		buf.writeBoolean(cancelInstead);
	}

	private static DetonatorFireC2SPacket read(RegistryByteBuf buf) {
		int slot = buf.readVarInt();
		boolean cancelInstead = buf.readBoolean();
		return new DetonatorFireC2SPacket(slot, cancelInstead);
	}
}
