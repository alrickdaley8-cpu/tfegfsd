package com.doomsday.nukes.network.packet;

import com.doomsday.nukes.DoomsdayNukes;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.BlockPos;

/**
 * Explicit stage-transition notice.
 *
 * <p>Timing is already implied by {@code DetonationS2CPacket} plus the shared world clock, so
 * this packet serves two narrow purposes: letting a client that joined mid-event snap to the
 * right stage, and driving one-shot client reactions (stage banner, siren stop, delayed boom)
 * from an event rather than a polling loop.</p>
 */
public record StageChangeS2CPacket(
			int detonationId,
			int stage,
			float timeSeconds,
			BlockPos origin
) implements CustomPayload {
	public static final CustomPayload.Id<StageChangeS2CPacket> ID =
		new CustomPayload.Id<>(DoomsdayNukes.id("stage_change"));

	public static final PacketCodec<RegistryByteBuf, StageChangeS2CPacket> CODEC =
		CustomPayload.codecOf((buf, payload) -> payload.writePayload(buf),
			StageChangeS2CPacket::read);

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
	}

	private void writePayload(RegistryByteBuf buf) {
		buf.writeVarInt(detonationId);
		buf.writeVarInt(stage);
		buf.writeFloat(timeSeconds);
		buf.writeBlockPos(origin);
	}

	private static StageChangeS2CPacket read(RegistryByteBuf buf) {
		int detonationId = buf.readVarInt();
		int stage = buf.readVarInt();
		float timeSeconds = buf.readFloat();
		BlockPos origin = buf.readBlockPos();
		return new StageChangeS2CPacket(detonationId, stage, timeSeconds, origin);
	}
}
