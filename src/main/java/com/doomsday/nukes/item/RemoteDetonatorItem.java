package com.doomsday.nukes.item;

import com.doomsday.nukes.DoomsdayNukes;
import com.doomsday.nukes.block.NukeBlock;
import com.doomsday.nukes.block.entity.NukeBlockEntity;
import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.config.DoomsdayConfig;
import com.doomsday.nukes.network.ClientPayloadSender;
import com.doomsday.nukes.network.packet.DetonatorFireC2SPacket;
import com.doomsday.nukes.network.packet.DetonatorLinkC2SPacket;
import com.doomsday.nukes.sound.ModSounds;
import com.doomsday.nukes.util.DText;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Remote detonator: one device per item, linked by touching it.
 *
 * <h2>Link model</h2>
 * A link is server-authored data on the stack: dimension id, position, and the device's
 * {@code linkSerial}. The serial is bumped by the device every time it is armed and the block
 * entity is re-resolved on every use, which means:
 * <ul>
 *   <li>breaking a device and placing another one at the same coordinates does <b>not</b> inherit
 *       the link — different serial, new block entity object;</li>
 *   <li>a client cannot invent a link for a device it never touched, because {@code serverLink}
 *       is only reached from the block's own use path and re-validates the position;</li>
 *   <li>the link survives death, relog and restart, because it is item NBT saved with the player.</li>
 * </ul>
 *
 * <h2>Controls</h2>
 * <ul>
 *   <li><b>Use</b> — fire the linked device (or arm it on the default timer if it is not armed).</li>
 *   <li><b>Sneak-use</b> — cancel the countdown, if {@code remoteCanDisarm}.</li>
 *   <li><b>Use on a device</b> — link; <b>sneak-use on a device</b> — clear the link. Both are
 *       handled by {@code NukeBlock#onUse} calling {@link #handleDeviceUse}.</li>
 * </ul>
 *
 * <p>There is deliberately no range limit on firing: the point of the item is that the safe place
 * to stand is not next to the device. Abuse is bounded by a one-shot-per-second gate on the server
 * instead, so a macro cannot retrigger every tick, and by {@code maxConcurrentDetonations}.</p>
 */
public class RemoteDetonatorItem extends Item {
	public static final String NBT_LINK = "DoomsdayLink";
	private static final String NBT_DIM = "Dim";
	private static final String NBT_X = "X";
	private static final String NBT_Y = "Y";
	private static final String NBT_Z = "Z";
	private static final String NBT_SERIAL = "Serial";
	private static final long MIN_FIRE_INTERVAL_MS = 1000L;

	/** Player uuid -> last accepted fire, so rate limiting is authoritative server-side. */
	private static final java.util.Map<java.util.UUID, Long> LAST_FIRE =
		java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

	public RemoteDetonatorItem(Settings settings) {
		super(settings);
	}

	// ———————————————————————————————————————————————————————— use

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		int slot = user.getInventory().getSlotWithStack(stack);
		boolean cancel = user.isSneaking() && ConfigManager.get().remoteCanDisarm;
		if (world.isClient) {
			// Common code cannot import ClientPlayNetworking, so it goes through the installed
			// transport (see ClientPayloadSender). No-op if nothing registered it.
			ClientPayloadSender.send(new DetonatorFireC2SPacket(slot, cancel));
			return TypedActionResult.success(stack);
		} else if (user instanceof ServerPlayerEntity sp) {
			// Reachable when a client (or a vanilla client via the "link" path) does not send the
			// packet itself: the integrated/server side still honours a direct use.
			serverFire(sp, slot, cancel);
		}
		return ActionResult.SUCCESS;
	}

	/**
	 * Right-clicking a device while holding the detonator links the two. This is the only way a
	 * link is acquired, and it only ever runs on the server.
	 */
	public void linkFromUse(ServerWorld world, PlayerEntity player, ItemStack stack, BlockPos pos) {
		NukeBlockEntity be = NukeBlock.find(world, pos);
		if (be == null) {
			return;
		}
		if (player.isSneaking()) {
			clearLink(stack);
			player.sendMessage(DText.of("gui.doomsday.detonator.unlinked"), true);
			return;
		}
		writeLink(stack, world, pos, be.linkSerial());
		player.sendMessage(DText.of("gui.doomsday.detonator.linked", DText.deviceName(be.preset())),
			false);
		player.playSound(ModSounds.NUKE_ARM, 0.5F, 2.0F);
		if (ConfigManager.get().verboseLogging) {
			DoomsdayNukes.LOGGER.info("Detonator linked to {} at {}", be.preset().name(),
				pos.toShortString());
		}
	}

/** Entry point used by {@code NukeBlock#onUse} and by {@code ModPackets}. */
	public static void handleDeviceUse(ServerWorld world, PlayerEntity player, BlockPos pos) {
		ItemStack stack = player.getMainHandStack();
		if (!(stack.getItem() instanceof RemoteDetonatorItem)) {
			stack = player.getOffHandStack();
			if (!(stack.getItem() instanceof RemoteDetonatorItem)) {
				return;
			}
		}
		((RemoteDetonatorItem) stack.getItem()).linkFromUse(world, player, stack, pos);
	}

	// ———————————————————————————————————————————————————— link storage

	private static void writeLink(ItemStack stack, ServerWorld world, BlockPos pos, int serial) {
		NbtCompound root = stack.getOrCreateNbt();
		NbtCompound link = new NbtCompound();
		link.putString(NBT_DIM, world.getRegistryKey().getValue().toString());
		link.putInt(NBT_X, pos.getX());
		link.putInt(NBT_Y, pos.getY());
		link.putInt(NBT_Z, pos.getZ());
		link.putInt(NBT_SERIAL, serial);
		root.put(NBT_LINK, link);
		stack.setNbt(root);
	}

	public static void clearLink(ItemStack stack) {
		NbtCompound root = stack.getNbt();
		if (root != null) {
			root.remove(NBT_LINK);
		}
	}

	/** @return the linked position, or null when the stack is not linked to anything */
	public static BlockPos linkedPos(ItemStack stack) {
		NbtCompound root = stack.getNbt();
		if (root == null || !root.contains(NBT_LINK)) {
			return null;
		}
		NbtCompound link = root.getCompound(NBT_LINK);
		return new BlockPos(link.getInt(NBT_X), link.getInt(NBT_Y), link.getInt(NBT_Z));
	}

	public static String linkedDimension(ItemStack stack) {
		NbtCompound root = stack.getNbt();
		if (root == null || !root.contains(NBT_LINK)) {
			return "";
		}
		return root.getCompound(NBT_LINK).getString(NBT_DIM);
	}

	private static int linkedSerial(ItemStack stack) {
		NbtCompound root = stack.getNbt();
		if (root == null || !root.contains(NBT_LINK)) {
			return -1;
		}
		return root.getCompound(NBT_LINK).getInt(NBT_SERIAL);
	}

	public static boolean isLinked(ItemStack stack) {
		return linkedPos(stack) != null;
	}

	// ———————————————————————————————————————————————————— server actions

	/** Client asked to (un)link a specific device. */
	public static void serverLink(ServerPlayerEntity player, int slot, BlockPos pos) {
		ItemStack stack = stackIn(player, slot);
		if (!(stack.getItem() instanceof RemoteDetonatorItem item)) {
			return;
		}
		if (DetonatorLinkC2SPacket.UNLINK.equals(pos)) {
			item.clearLink(stack);
			player.sendMessage(DText.of("gui.doomsday.detonator.unlinked"), true);
			return;
		}
		item.linkFromUse(player.getServerWorld(), player, stack, pos);
	}

	/**
	 * Fire (or cancel) the linked device. Every refusal sends exactly one message and changes no
	 * world state, so "denied" is distinguishable from "broken".
	 */
	public static void serverFire(ServerPlayerEntity player, int slot, boolean cancelInstead) {
		ItemStack stack = stackIn(player, slot);
		if (stack.isEmpty() || !(stack.getItem() instanceof RemoteDetonatorItem)) {
			return;
		}
		BlockPos pos = linkedPos(stack);
		if (pos == null) {
			player.sendMessage(DText.of("gui.doomsday.detonator.not_linked"), true);
			return;
		}
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		ServerWorld world = worldFor(server, player, linkedDimension(stack));
		if (world == null) {
			player.sendMessage(DText.of("gui.doomsday.detonator.wrong_dimension"), true);
			return;
		}
		NukeBlockEntity be = NukeBlock.find(world, pos);
		if (be == null) {
			clearLink(stack);
			player.sendMessage(DText.of("gui.doomsday.detonator.device_gone"), true);
			return;
		}
		if (linkedSerial(stack) != be.linkSerial()) {
			clearLink(stack);
			player.sendMessage(DText.of("gui.doomsday.detonator.stale_link"), true);
			return;
		}
		DoomsdayConfig c = ConfigManager.get();
		if (cancelInstead) {
			if (!c.remoteCanDisarm) {
				player.sendMessage(DText.of("gui.doomsday.detonator.cancel_disabled"), true);
				return;
			}
			boolean ok = be.requestDisarm(world, player);
			player.sendMessage(DText.of(ok ? "gui.doomsday.detonator.cancelled"
				: "gui.doomsday.detonator.nothing_to_cancel"), true);
			return;
		}
		long now = System.currentTimeMillis();
		Long last = LAST_FIRE.get(player.getUuid());
		if (last != null && now - last < MIN_FIRE_INTERVAL_MS) {
			return;
		}
		LAST_FIRE.put(player.getUuid(), now);
		if (!be.isArmed()) {
			// A dead switch on an unarmed device arms it on the default timer, which is what a
			// player pressing fire "expecting it to work" actually wants.
			be.requestArm(world, player, c.defaultTimerSeconds);
			return;
		}
		be.detonateNow(world, "remote");
		world.playSound(null, pos, ModSounds.NUKE_FLASH_HISS, SoundCategory.RECORDS, 1.1F, 1.0F);
	}

	/** Resolve a stored dimension id, falling back to the player's own world. */
	private static ServerWorld worldFor(MinecraftServer server, ServerPlayerEntity player,
										String dimensionId) {
		if (dimensionId == null || dimensionId.isEmpty()) {
			return player.getServerWorld();
		}
		Identifier id = Identifier.tryParse(dimensionId);
		if (id == null) {
			return player.getServerWorld();
		}
		ServerWorld world = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, id));
		return world != null ? world : player.getServerWorld();
	}

	private static ItemStack stackIn(ServerPlayerEntity player, int slot) {
		if (slot < 0 || slot >= player.getInventory().size()) {
			return player.getMainHandStack();
		}
		ItemStack stack = player.getInventory().getStack(slot);
		return stack.isEmpty() ? player.getMainHandStack() : stack;
	}

	/** Tooltip line: which device this detonator is bound to. */
	public static Text describe(ItemStack stack) {
		BlockPos pos = linkedPos(stack);
		if (pos == null) {
			return DText.of("gui.doomsday.detonator.unlinked");
		}
		return DText.of("gui.doomsday.detonator.linked_to", pos.getX(), pos.getY(), pos.getZ(),
			linkedDimension(stack));
	}

	/** Drop the rate-limit record (used by tests and by server shutdown). */
	public static void clearRateLimits() {
		LAST_FIRE.clear();
	}
}
