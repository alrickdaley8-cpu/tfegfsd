package com.doomsday.nukes.network.packet;

import com.doomsday.nukes.DoomsdayNukes;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.BlockPos;

/**
 * Links the remote detonator in hotbar {@code slot} to one device, or clears the link.
 *
 * <p>The link lives in the item stack's NBT and is written by the <em>server</em>, so it survives
 * death and relog and cannot be forged into controlling a device the player never touched: the
 * only way to obtain a link is to right-click that device while holding the detonator.</p>
 */
public record DetonatorLinkC2SPacket(
			BlockPos pos,
			int slot
) implements CustomPayload {
	public static final CustomPayload.Type<DetonatorLinkC2SPacket> TYPE =
		new CustomPayload.Type<>(DoomsdayNukes.id("detonator_link"));

	public static final PacketCodec<RegistryByteBuf, DetonatorLinkC2SPacket> CODEC =
		PacketCodec.uniform(DetonatorLinkC2SPacket::writePayload, DetonatorLinkC2SPacket::read);

	@Override
	public CustomPayload.Type<? extends CustomPayload> type() {
		return TYPE;
	}

	private void writePayload(RegistryByteBuf buf) {
		buf.writeBlockPos(pos);
		buf.writeVarInt(slot);
	}

	private static DetonatorLinkC2SPacket read(RegistryByteBuf buf) {
		BlockPos pos = buf.readBlockPos();
		int slot = buf.readVarInt();
		return new DetonatorLinkC2SPacket(pos, slot);
	}

	/** Sentinel position meaning 'unlink the detonator'. */
	public static final BlockPos UNLINK = new BlockPos(0, -2147483648, 0);
}
