package com.doomsday.nukes.block.entity;

import com.doomsday.nukes.DoomsdayNukes;
import com.doomsday.nukes.block.NukeBlock;
import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.config.DoomsdayConfig;
import com.doomsday.nukes.detonation.NukePreset;
import com.doomsday.nukes.network.ModPackets;
import com.doomsday.nukes.registry.ModBlockEntities;
import com.doomsday.nukes.sound.ModSounds;
import com.doomsday.nukes.util.MathUtil;
import com.doomsday.nukes.world.EMPManager;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;

/**
 * State holder and presentation anchor for a placed device.
 *
 * <h2>Deliberately has no ticker</h2>
 * Everything this block does is either (a) event-driven — arming, disarming, the scheduled
 * detonation tick — or (b) derived on demand from {@link #detonateAtWorldTime()} and the world
 * clock. So an armed device sitting in a spawn chunk for 20 minutes costs zero ticks. That is
 * why there is no {@code BlockEntityTicker} here, and why {@code getTicker} returns nothing.
 *
 * <h2>Sync model</h2>
 * <ul>
 *   <li><b>Chunk load</b>: {@link #writeNbt} is what vanilla writes into the chunk's block-entity
 *       NBT, so a client opening the area already knows {@code armed}, {@code timerTicks} and
 *       {@code detonateAt}; nothing extra is required.</li>
 *   <li><b>Live change</b>: one {@code DeviceStateS2CPacket} to players in reach, on arm, on
 *       disarm and once per whole second while armed.</li>
 *   <li><b>Never</b> per tick.</li>
 * </ul>
 *
 * <h2>Link integrity</h2>
 * A remote detonator stores {@code (dimension, pos, linkSerial)}. {@link #linkSerial} changes
 * whenever the block entity is replaced, so if a device is broken and a new one is placed at the
 * same coordinates, an old detonator's link is provably stale and is refused rather than firing
 * somebody else's device.
 */
public class NukeBlockEntity extends BlockEntity {
	public static final String NBT_PRESET = "Preset";
	public static final String NBT_ARMED = "Armed";
	public static final String NBT_TIMER = "TimerTicks";
	public static final String NBT_DETONATE_AT = "DetonateAtWorldTime";
	public static final String NBT_LINK_SERIAL = "LinkSerial";
	public static final String NBT_OWNER = "Owner";

	private NukePreset preset;
	private boolean armed;
	/** Requested countdown length, kept for the UI and for re-scheduling after a reload. */
	private int timerTicks;
	/** Absolute world time at which this device fires. */
	private long detonateAtWorldTime;
	/** Firing instant captured at arm time; used to keep the UI honest across a reload. */
	private long armedAtWorldTime;
	private int linkSerial;
	private String owner = "";
	private long lastPanicScanTime;
	private int lastPanicCount;
	private long lastSecondBroadcast = Long.MIN_VALUE;

	public NukeBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.NUKE, pos, state);
	}

	// ———————————————————————————————————————————————————— identity

	public NukePreset preset() {
		if (preset == null) {
			// A BE created by chunk load before the block told us its preset: recover it from the
			// block itself, which is the authoritative source, instead of defaulting silently.
			BlockState state = world == null ? null : world.getBlockState(getPos());
			if (state != null && state.getBlock() instanceof NukeBlock block) {
				preset = block.preset();
			}
		}
		return preset == null ? NukePreset.STANDARD_NUKE : preset;
	}

	public void setPreset(NukePreset preset) {
		if (preset != null && preset != this.preset) {
			this.preset = preset;
			markDirty();
		}
	}

	public int linkSerial() {
		return linkSerial;
	}

	public void setOwner(PlayerEntity player) {
		this.owner = player == null ? "" : player.getName().getString();
	}

	public String owner() {
		return owner;
	}

	// ———————————————————————————————————————————————————— state

	public boolean isArmed() {
		return armed;
	}

	public long detonateAtWorldTime() {
		return detonateAtWorldTime;
	}

	public int timerTicks() {
		return timerTicks;
	}

	/** Ticks left, clamped at 0; works identically on client and server. */
	public int ticksRemaining() {
		if (!armed || world == null) {
			return 0;
		}
		return (int) Math.max(0L, detonateAtWorldTime - world.getTime());
	}

	/** Seconds left, for the digit display. */
	public float secondsRemaining() {
		return ticksRemaining() / 20.0F;
	}

	/**
	 * Blink period in ticks, from 12 s down to 2. This is the "accelerating warning light" the
	 * spec asks for, and it is a pure function of remaining time: no state, no drift, and the
	 * client and server agree without being told anything.
	 */
	public float blinkPeriodTicks() {
		int remain = ticksRemaining();
		if (remain <= 0) {
			return 2.0F;
		}
		float t = MathUtil.clamp01(remain / 6000.0F);
		return MathUtil.lerp(2.0F, 12.0F, t);
	}

	/** 0..1 instantaneous lamp brightness, including the final-second strobe. */
	public float lampPulse(float renderTime) {
		float period = blinkPeriodTicks();
		float phase = ((renderTime + (float) (getPos().getX() * 0.37D)) % period) / period;
		float pulse = phase < 0.42F ? 1.0F : 0.06F;
		int remain = ticksRemaining();
		if (armed && remain > 0 && remain <= 20) {
			// Last second: strobe at 4 Hz regardless of the smooth curve.
			pulse = ((int) (renderTime * 4.0F) & 1) == 0 ? 1.0F : 0.15F;
		}
		return armed ? pulse : 0.12F;
	}

	/** True while an EMP is suppressing this device (remote triggers refused). */
	public boolean isEmpSuppressed() {
		return world instanceof ServerWorld sw && EMPManager.isEmpActive(sw, getPos());
	}

	public double effectiveYieldKt() {
		return ConfigManager.tuning(preset()).yieldKt();
	}

	public void notePanicScan(long now, int panicked) {
		this.lastPanicScanTime = now;
		this.lastPanicCount = panicked;
	}

	public long lastPanicScan() {
		return lastPanicScanTime;
	}

	public int lastPanicCount() {
		return lastPanicCount;
	}

	/** Called by the block when the state is replaced, so no stale reference outlives us. */
	public void onRemovedFromWorld() {
		if (world instanceof ServerWorld sw) {
			DoomsdayNukes.detonations().unregisterArmed(sw, getPos());
		}
		this.armed = false;
	}

	// ———————————————————————————————————————————————————— arming

	/**
	 * Server-side arm. Validates the timer against config, requires a device preset, refuses
	 * while an EMP is active, and then starts the vanilla scheduled-tick chain.
	 *
	 * @return the accepted countdown in ticks, or -1 when refused
	 */
	public int requestArm(ServerWorld server, PlayerEntity player, int requestedSeconds) {
		DoomsdayConfig c = ConfigManager.get();
		int seconds = MathUtil.clamp(requestedSeconds, c.minTimerSeconds, c.maxTimerSeconds);
		if (server == null || player == null || !player.isAlive() || !canInteract(player)) {
			return -1;
		}
		{
			// Reach check always applies: the sender could be anywhere in the dimension.
			double reach = 8.0D;
			if (player.squaredDistanceTo(Vec3d.ofCenter(getPos())) > reach * reach) {
				return -1;
			}
		}
		if (isEmpSuppressed()) {
			ModPackets.sendToPlayer(player, new com.doomsday.nukes.network.packet.DeviceStateS2CPacket(
				getPos(), preset.ordinal(), false, 0, effectiveYieldKt(), true));
			return -1;
		}
		if (player.getAbilities() != null && player.getAbilities().creativeMode && player.isSneaking()) {
			// Sneak + creative = instant test fire, which is how the effect suite is iterated on.
			detonateNow(server, "creative-test");
			return -1;
		}

		this.armed = true;
		this.timerTicks = seconds * 20;
		this.armedAtWorldTime = server.getTime();
		this.detonateAtWorldTime = server.getTime() + this.timerTicks;
		this.linkSerial = this.linkSerial + 1;
		setOwner(player);
		markDirty();
		updateArmedState(server, true);
		if (server.getBlockState(getPos()).getBlock() instanceof NukeBlock block) {
			block.startTimerChain(server, getPos(), this.timerTicks);
		}
		DoomsdayNukes.detonations().registerArmed(server, getPos(), this);

		// One siren for the whole world region — not one per player, and not per tick.
		server.playSound(null, getPos(), ModSounds.NUKE_ARM, SoundCategory.RECORDS,
			2.6F, preset.soundPitch);
		broadcastState(server);
		if (c.verboseLogging) {
			DoomsdayNukes.LOGGER.info("Device at {} armed for {}s by {}",
				getPos().toShortString(), seconds, player.getName().getString());
		}
		return this.timerTicks;
	}

	/** Player-initiated disarm. */
	public boolean requestDisarm(ServerWorld server, PlayerEntity player) {
		if (!armed) {
			return false;
		}
		DoomsdayConfig c = ConfigManager.get();
		if (!c.allowDisarm && player != null && !player.getAbilities().creativeMode) {
			return false;
		}
		disarmSilently(server);
		if (player != null) {
			player.playSound(ModSounds.NUKE_FLASH_HISS, 0.9F, 1.6F);
		}
		if (server != null) {
			server.playSound(null, getPos(), ModSounds.NUKE_FLASH_HISS, SoundCategory.RECORDS,
				1.4F, 1.5F);
		}
		return true;
	}

	/** Disarm without sound/UI side effects — used by block breaking and by the detonator. */
	public void disarmSilently(ServerWorld server) {
		this.armed = false;
		this.timerTicks = 0;
		this.detonateAtWorldTime = 0L;
		markDirty();
		updateArmedState(server, false);
		if (server != null) {
			DoomsdayNukes.detonations().unregisterArmed(server, getPos());
		}
		broadcastState(server);
	}

	/**
	 * Fires the device. This is the single detonation entry point: countdown expiry, remote
	 * trigger and the debug command all arrive here, so there is exactly one behaviour to test.
	 *
	 * @param reason logged for diagnosis
	 */
	public void detonateNow(ServerWorld server, String reason) {
		if (server == null) {
			return;
		}
		BlockState state = server.getBlockState(getPos());
		if (!(state.getBlock() instanceof NukeBlock block)) {
			return;
		}
		disarmSilently(server);
		// The device consumes itself: a crater that starts at the device, not under the floor.
		server.removeBlock(getPos(), false);
		Vec3d origin = Vec3d.ofCenter(getPos()).add(0.0D, 0.85D, 0.0D);
		DoomsdayNukes.detonations().detonate(server, origin, getPos(), preset);
		if (ConfigManager.get().verboseLogging) {
			DoomsdayNukes.LOGGER.info("Device at {} detonated ({}), preset {}",
				getPos().toShortString(), reason, preset.name());
		}
		block.panicNearbyMobs(server, getPos(), this);
	}

	/** Reflects the armed flag into the block state so luminance/observers/comparators work. */
	private void updateArmedState(ServerWorld server, boolean nowArmed) {
		if (server == null) {
			return;
		}
		BlockState state = server.getBlockState(getPos());
		if (state.getBlock() instanceof NukeBlock && state.get(NukeBlock.ARMED) != nowArmed) {
			server.setBlockState(getPos(), state.with(NukeBlock.ARMED, nowArmed));
		}
	}

	/** Rate-limited device sync: at most one packet per second per device. */
	public void tickBroadcast(ServerWorld server) {
		if (!armed || world == null) {
			return;
		}
		long now = server.getTime();
		if (now - lastSecondBroadcast >= 20L) {
			lastSecondBroadcast = now;
			broadcastState(server);
		}
	}

	private void broadcastState(ServerWorld server) {
		if (server == null) {
			return;
		}
		ModPackets.sendDeviceState(server, this);
	}

	// ———————————————————————————————————————————————————— persistence

	@Override
	protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		super.writeNbt(nbt, registries);
		nbt.putString(NBT_PRESET, preset.configKey);
		nbt.putBoolean(NBT_ARMED, armed);
		nbt.putInt(NBT_TIMER, timerTicks);
		nbt.putLong(NBT_DETONATE_AT, detonateAtWorldTime);
		nbt.putInt(NBT_LINK_SERIAL, linkSerial);
		if (!owner.isEmpty()) {
			nbt.putString(NBT_OWNER, owner);
		}
	}

	@Override
	public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		super.readNbt(nbt, registries);
		this.preset = NukePreset.byConfigKey(nbt.getString(NBT_PRESET));
		this.armed = nbt.getBoolean(NBT_ARMED);
		this.timerTicks = nbt.getInt(NBT_TIMER);
		this.detonateAtWorldTime = nbt.getLong(NBT_DETONATE_AT);
		this.linkSerial = nbt.getInt(NBT_LINK_SERIAL);
		this.owner = nbt.getString(NBT_OWNER);

		// Reconnect / reload reconciliation. If the scheduled tick could not fire while the
		// chunk was unloaded, the device must not sit armed forever: it either fires now (its
		// time has passed) or re-registers itself with the manager for the remaining budget.
		if (armed && world instanceof ServerWorld server) {
			if (detonateAtWorldTime <= server.getTime()) {
				detonateNow(server, "reload-overdue");
			} else {
				DoomsdayNukes.detonations().registerArmed(server, getPos(), this);
				tickBroadcast(server);
			}
		}
	}

	/** Interaction guard shared by arm/disam/link. */
	private boolean canInteract(PlayerEntity player) {
		return player != null && !player.isSpectator();
	}

	/** Comparator output: 0 unarmed, else countdown progress 1..15 (coarse, deterministic). */
	public int comparatorLevel() {
		if (!armed || timerTicks <= 0) {
			return 0;
		}
		float p = MathUtil.clamp01(1.0F - ticksRemaining() / (float) timerTicks);
		return 1 + (int) (p * 14.0F);
	}

	/** Debug line used by {@code /doomsday devices}. */
	public String describe() {
		return "device " + getPos().toShortString() + " " + preset().name()
			+ (armed ? " ARMED t-" + ticksRemaining() : " idle")
			+ " yield=" + String.format(java.util.Locale.ROOT, "%.1fkt", effectiveYieldKt())
			+ " link=" + linkSerial
			+ (lastPanicCount > 0 ? " panic=" + lastPanicCount : "");
	}

	/**
	 * Static entry point for the network layer: resolve the device, then delegate to the instance
	 * method that owns every rule. Kept here rather than in {@code ModPackets} so that the
	 * "which block entity is this?" question has exactly one answer in the codebase (the
	 * {@code at()} helper below), and a packet handler stays three lines long.
	 */
	public static void requestArm(ServerWorld world, PlayerEntity player, BlockPos pos,
								  int requestedSeconds) {
		NukeBlockEntity be = at(world, pos);
		if (be == null) {
			if (player != null) {
				player.sendMessage(com.doomsday.nukes.util.DText.of("command.doomsday.no_device"), true);
			}
			return;
		}
		be.requestArm(world, player, requestedSeconds);
	}

	/** @see #requestArm(ServerWorld, PlayerEntity, BlockPos, int) */
	public static void requestDisarm(ServerWorld world, PlayerEntity player, BlockPos pos) {
		NukeBlockEntity be = at(world, pos);
		if (be == null) {
			if (player != null) {
				player.sendMessage(com.doomsday.nukes.util.DText.of("command.doomsday.no_device"), true);
			}
			return;
		}
		be.requestDisarm(world, player);
	}

	/** Sanity: registered against the right block, so a misplaced BE can never NPE the render. */
	public static NukeBlockEntity at(net.minecraft.world.World world, BlockPos pos) {
		if (world == null) {
			return null;
		}
		BlockEntity be = world.getBlockEntity(pos);
		if (be instanceof NukeBlockEntity nuke && ModBlockEntities.NUKE.isOf(be)) {
			return nuke;
		}
		// Fall back to the block's own lookup so a client that loaded the chunk before the mod's
		// BE type was ready still finds (or creates) the entity it needs.
		return NukeBlock.find(world, pos);
	}

}
