package com.doomsday.nukes.network.packet;

import com.doomsday.nukes.DoomsdayNukes;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/**
 * THE detonation packet: everything a client needs to reconstruct an entire nuclear event
 * locally, from flash through the last wisp of cloud, with no further traffic.
 *
 * <h2>Why one packet instead of many</h2>
 * <ul>
 *   <li><b>Absolute clock anchoring.</b> {@code startWorldTime} is the server's
 *       {@code World#getTime()} at T0. Clients already track that value through vanilla time
 *       sync, so every stage boundary is resolvable locally as {@code worldTime - startWorldTime}.
 *       No drift, no per-tick messages, and a client that joins mid-event computes the same stage
 *       as one that was there from the start.</li>
 *   <li><b>Server-authored timing.</b> The stage windows are sent as numbers rather than being
 *       re-derived from the client's config, so a client running different
 *       {@code cloudLifetimeSeconds} still fades its cloud at the same moment the server ends
 *       the contamination. Clients use their own config only for <em>quality</em>.</li>
 *   <li><b>Fixed size.</b> 32 fields, all primitive: about 150 bytes. Compare with the naive
 *       "spawn 4000 particles and sync them" design, which is megabytes per second per player.</li>
 * </ul>
 *
 * <p>Size fields are already yield-scaled by the server, so the client never needs the config to
 * draw a correctly-proportioned fireball.</p>
 */
public record DetonationS2CPacket(
		int detonationId,
		int presetOrdinal,
		double x,
		double y,
		double z,
		int blockX,
		int blockY,
		int blockZ,
		long startWorldTime,
		float yieldKt,
		float flashStart,
		float flashLength,
		float fireballStart,
		float fireballLength,
		float fireballGrow,
		float shockwaveStart,
		float shockwaveLength,
		float cloudStart,
		float cloudLength,
		float falloutStart,
		float falloutLength,
		float aftermathStart,
		float aftermathLength,
		float totalLength,
		float fireballRadius,
		float shockwaveRadius,
		float craterRadius,
		float cloudScale,
		float flashDistance,
		float flashWhiteout,
		float flashDesaturate,
		float skyDarkness,
		float shakeSeconds,
		boolean flashEnabled,
		boolean cloudEnabled,
		boolean falloutEnabled,
		boolean atmosphereEnabled
) implements CustomPayload {
	public static final CustomPayload.Id<DetonationS2CPacket> ID =
		new CustomPayload.Id<>(DoomsdayNukes.id("detonation"));

	public static final PacketCodec<RegistryByteBuf, DetonationS2CPacket> CODEC =
		CustomPayload.codecOf(DetonationS2CPacket::writePayload,
			DetonationS2CPacket::read);

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
	}

	private void writePayload(RegistryByteBuf buf) {
		buf.writeVarInt(detonationId);
		buf.writeVarInt(presetOrdinal);
		buf.writeDouble(x);
		buf.writeDouble(y);
		buf.writeDouble(z);
		buf.writeVarInt(blockX);
		buf.writeVarInt(blockY);
		buf.writeVarInt(blockZ);
		buf.writeLong(startWorldTime);
		buf.writeFloat(yieldKt);
		buf.writeFloat(flashStart);
		buf.writeFloat(flashLength);
		buf.writeFloat(fireballStart);
		buf.writeFloat(fireballLength);
		buf.writeFloat(fireballGrow);
		buf.writeFloat(shockwaveStart);
		buf.writeFloat(shockwaveLength);
		buf.writeFloat(cloudStart);
		buf.writeFloat(cloudLength);
		buf.writeFloat(falloutStart);
		buf.writeFloat(falloutLength);
		buf.writeFloat(aftermathStart);
		buf.writeFloat(aftermathLength);
		buf.writeFloat(totalLength);
		buf.writeFloat(fireballRadius);
		buf.writeFloat(shockwaveRadius);
		buf.writeFloat(craterRadius);
		buf.writeFloat(cloudScale);
		buf.writeFloat(flashDistance);
		buf.writeFloat(flashWhiteout);
		buf.writeFloat(flashDesaturate);
		buf.writeFloat(skyDarkness);
		buf.writeFloat(shakeSeconds);
		buf.writeBoolean(flashEnabled);
		buf.writeBoolean(cloudEnabled);
		buf.writeBoolean(falloutEnabled);
		buf.writeBoolean(atmosphereEnabled);
	}

	private static DetonationS2CPacket read(RegistryByteBuf buf) {
		return new DetonationS2CPacket(
			buf.readVarInt(),
			buf.readVarInt(),
			buf.readDouble(),
			buf.readDouble(),
			buf.readDouble(),
			buf.readVarInt(),
			buf.readVarInt(),
			buf.readVarInt(),
			buf.readLong(),
			buf.readFloat(),
			buf.readFloat(),
			buf.readFloat(),
			buf.readFloat(),
			buf.readFloat(),
			buf.readFloat(),
			buf.readFloat(),
			buf.readFloat(),
			buf.readFloat(),
			buf.readFloat(),
			buf.readFloat(),
			buf.readFloat(),
			buf.readFloat(),
			buf.readFloat(),
			buf.readFloat(),
			buf.readFloat(),
			buf.readFloat(),
			buf.readFloat(),
			buf.readFloat(),
			buf.readFloat(),
			buf.readFloat(),
			buf.readFloat(),
			buf.readFloat(),
			buf.readFloat(),
			buf.readBoolean(),
			buf.readBoolean(),
			buf.readBoolean(),
			buf.readBoolean());
	}

	/** Seconds since T0 for the client's current clock, clamped to the event window. */
	public float timeSince(long clientWorldTime) {
		long delta = clientWorldTime - startWorldTime;
		if (delta <= 0L) {
			return 0.0F;
		}
		return Math.min(totalLength + 0.5F, delta / 20.0F);
	}
}
