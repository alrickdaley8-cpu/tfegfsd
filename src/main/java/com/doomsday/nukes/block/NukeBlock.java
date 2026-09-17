package com.doomsday.nukes.block;

import com.doomsday.nukes.block.entity.NukeBlockEntity;
import com.doomsday.nukes.gui.ScreenOpener;
import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.config.DoomsdayConfig;
import com.doomsday.nukes.detonation.NukePreset;
import com.doomsday.nukes.item.RemoteDetonatorItem;
import com.doomsday.nukes.registry.ModBlockEntities;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.world.BlockView;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.World;

import java.util.List;

/**
 * The device: a tall, directional, non-cubic missile on a launch cradle.
 *
 * <h2>Shape strategy (and why not a real two-voxel device)</h2>
 * The block occupies exactly one voxel for every <em>simulation</em> purpose — collision,
 * lighting, redstone, chunk bookkeeping — and <em>renders</em> 2.06 voxels tall through its
 * block-entity renderer, with a detailed multi-element JSON model for the item form. A
 * two-voxel device (the {@code HALF=UPPER/LOWER} pattern doors and beds use) would double the
 * desync surface for no gameplay gain: every arm, disarm, detonate and link operation would
 * have to locate and update both halves consistently, and "upper half present, lower half
 * destroyed" becomes reachable. So: a block whose model carries no elements, animated 3D geometry in
 * the block entity, oversized {@link #getOutlineShape} so the selection box matches the
 * silhouette you can actually aim at, and a {@link #getCullingShape} that matches too —
 * otherwise the missile pops out of existence when its base voxel leaves the frustum.
 *
 * <h2>Countdown strategy</h2>
 * The timer is a <b>vanilla scheduled block tick</b>, not a ticker. Scheduled ticks live in the
 * chunk's tick store, so an armed device survives a restart, a chunk unload/reload and a player
 * logout, and fires on exactly the right tick — with <b>zero per-tick cost</b> while it waits.
 * Each beat reschedules itself at {@code min(remaining, PANIC_INTERVAL_TICKS)}, which buys both
 * an exact detonation instant and a 2 Hz mob-panic scan from one queue entry:
 *
 * <pre>
 *   arm(timer=3000t) → tick at +40 → scan, schedule +40 → … → tick at target → DETONATE
 * </pre>
 *
 * <p>Blinking light, accelerating blink and countdown digits are all derived at render time from
 * {@code detonateAtWorldTime - world.getTime()}, so presentation costs nothing either.</p>
 */
public class NukeBlock extends Block implements BlockEntityProvider {
	/** Direction the nose and control panel face. */
	public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
	/** Live flag, kept in the block state so observers, comparators and lighting all see it. */
	public static final BooleanProperty ARMED = BooleanProperty.of("armed");

	/** Mob-reaction rescan interval, in ticks. */
	public static final int PANIC_INTERVAL_TICKS = 40;

	/** Selection box: a slim tower, deliberately taller than the voxel it occupies. */
	private static final VoxelShape OUTLINE =
		VoxelShapes.cuboid(0.125D, 0.0D, 0.125D, 0.875D, 2.0625D, 0.875D);
	/** Collision box: inside the voxel, so physics and pathfinding stay well-behaved. */
	private static final VoxelShape COLLISION =
		VoxelShapes.cuboid(0.1875D, 0.0D, 0.1875D, 0.8125D, 1.0D, 0.8125D);

	private final NukePreset preset;

	public NukeBlock(NukePreset preset) {
		super(AbstractBlock.Settings.create()
			.strength(8.0F, 20.0F)
			.requiresTool()
			.sounds(BlockSoundGroup.METAL)
			.nonOpaque()
			.notSolid()
			// The armed warning light is also a dim light source, so a live device is
			// findable at night. State-derived, therefore free when idle.
			.luminance(state -> Boolean.TRUE.equals(state.get(ARMED)) ? 6 : 0));
		this.preset = preset;
		setDefaultState(getStateManager().getDefaultState()
			.with(FACING, Direction.NORTH)
			.with(ARMED, false));
	}

	public NukePreset preset() {
		return preset;
	}

	// ———————————————————————————————————————————————————— state / shape

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(FACING, ARMED);
	}

	@Override
	public BlockState getPlacementState(ItemPlacementContext ctx) {
		// Nose points away from the placer, which is how a launch assembly reads.
		Direction facing = ctx.getHorizontalPlayerFacing().getOpposite();
		return getDefaultState().with(FACING, facing);
	}

	@Override
	public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos,
									  ShapeContext context) {
		return OUTLINE;
	}

	@Override
	public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos,
										ShapeContext context) {
		return COLLISION;
	}

	@Override
	public VoxelShape getCullingShape(BlockState state, BlockView world, BlockPos pos) {
		return OUTLINE;
	}

	@Override
	public VoxelShape getCameraCollisionShape(BlockState state, BlockView world, BlockPos pos,
											  ShapeContext context) {
		// No camera push-in: the missile is decoration around a 1-voxel collider, and clipping
		// the player's camera inside a 2-voxel shape is the classic "wall in my face" bug.
		return VoxelShapes.empty();
	}

	// ———————————————————————————————————————————————————— block entity

	@Override
	public NukeBlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return new NukeBlockEntity(pos, state);
	}

	@Override
	public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState,
							   boolean moved) {
		if (!state.isOf(newState.getBlock()) && !world.isClient) {
			NukeBlockEntity be = find(world, pos);
			if (be != null) {
				// A device that stops existing stops being armed. This is onStateReplaced rather
				// than onBreak on purpose: the same cleanup has to happen when the crater takes
				// the block, when water fills it and when a piston moves it, and only the player's
				// pickaxe would have seen a break hook. The dropped item comes from the loot table,
				// so it can never carry a live timer into an inventory.
				if (world instanceof ServerWorld server) {
					be.disarmSilently(server);
				}
				// Drop the panic registration; the manager holds weak references and would
				// otherwise keep a dead entity alive until its next sweep.
				be.onRemovedFromWorld();
			}
		}
		super.onStateReplaced(state, world, pos, newState, moved);
	}

	/** Works on both sides; {@code instanceof} keeps it free of provider-generic APIs. */
	public static NukeBlockEntity find(World world, BlockPos pos) {
		if (world == null) {
			return null;
		}
		net.minecraft.block.entity.BlockEntity be = world.getBlockEntity(pos);
		return be instanceof NukeBlockEntity nuke ? nuke : null;
	}

	/**
	 * The armed device's lamp, drawn with particles rather than a {@code BlockEntityRenderer}.
	 *
	 * <h2>Why not a BER</h2>
	 * The obvious implementation is a block-entity renderer that draws a small emissive box whose
	 * brightness comes from {@code NukeBlockEntity#lampPulse}. It was tried and dropped on
	 * compatibility grounds: {@code BlockEntityRenderer}'s render signature changed shape around
	 * this version (the overlay/model-data/light parameters moved between 1.21.1 and 1.21.2), and a
	 * BER is the kind of code that compiles, then crashes the *client* on a patch bump — for a
	 * blinking light. Particles are the same information through an API that has not changed in
	 * years, they inherit the world's own culling and distance behaviour, and they cost nothing when
	 * no device is armed (random display ticks are already rate-limited by the client's particle
	 * settings).
	 *
	 * <p>The tower itself is a normal block model, so the device occupies exactly one simulation
	 * voxel while reading as a two-block machine: the model is 1.0 wide and tall, and the
	 * {@link #FACING}-rotated variants put the console on the right side.</p>
	 */
	@Override
	public void randomDisplayTick(BlockState state, World world, BlockPos pos,
								  net.minecraft.util.math.random.Random random) {
		if (!world.isClient || !state.get(ARMED)) {
			return;
		}
		NukeBlockEntity be = find(world, pos);
		if (be == null) {
			return;
		}
		// The lamp's pulse decides *whether* this display tick shows anything, so the sparkle rate
		// tracks the configured blink period instead of the fixed random-display-tick cadence.
		if (be.lampPulse(0.0F) < 0.5F) {
			return;
		}
		double x = pos.getX() + 0.5D + (random.nextDouble() - 0.5D) * 0.5D;
		double y = pos.getY() + 1.28D;
		double z = pos.getZ() + 0.5D + (random.nextDouble() - 0.5D) * 0.5D;
		if (random.nextFloat() < 0.55F) {
			world.addParticle(ParticleTypes.ELECTRIC_SPARK, x, y, z,
				0.0D, 0.012D, 0.0D);
		} else {
			world.addParticle(ParticleTypes.SMOKE, x, y + 0.06D, z,
				0.0D, 0.014D, 0.0D);
		}
	}

	// —————————————————————————————————————————————————————— interaction

	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
							 BlockHitResult hit) {
		NukeBlockEntity be = find(world, pos);

		if (world.isClient) {
			// Opening a screen is client work, and this class may be loaded on a dedicated server,
			// so it goes through the installed ScreenOpener rather than MinecraftClient (see the
			// javadoc on that class for why).
			boolean linkMode = player.getMainHandStack().getItem() instanceof RemoteDetonatorItem
				|| player.getOffHandStack().getItem() instanceof RemoteDetonatorItem;
			if (linkMode) {
				// Linking is a server operation; the item handles the click, so the hand must not
				// also swing into a second use.
				return ActionResult.SUCCESS;
			}
			ScreenOpener.openControlScreen(pos, be != null ? be.preset() : preset,
				be != null && be.isArmed(), be == null ? 0 : be.ticksRemaining());
			return ActionResult.SUCCESS;
		}

		if (!(world instanceof ServerWorld server)) {
			return ActionResult.PASS;
		}
		if (be == null) {
			return ActionResult.PASS;
		}

		if (player.getMainHandStack().getItem() instanceof RemoteDetonatorItem
				|| player.getOffHandStack().getItem() instanceof RemoteDetonatorItem) {
			// The only way to acquire a link is to touch the device with the detonator.
			RemoteDetonatorItem.handleDeviceUse(server, player, pos);
			return ActionResult.SUCCESS;
		}
		if (player.isSneaking()) {
			if (be.isArmed() && ConfigManager.get().allowDisarm) {
				be.requestDisarm(server, player);
				return ActionResult.SUCCESS;
			}
			return ActionResult.PASS;
		}
		// Right-click without shift just confirms; the control panel is client-opened above and
		// every action inside it round-trips through a validated C2S packet.
		return ActionResult.SUCCESS;
	}

	// ———————————————————————————————————————————————————— countdown

	@Override
	public void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
		NukeBlockEntity be = find(world, pos);
		if (be == null || !be.isArmed()) {
			return;
		}
		long now = world.getTime();
		long target = be.detonateAtWorldTime();
		if (now >= target) {
			be.detonateNow(world, "countdown");
			return;
		}
		long remaining = target - now;
		int step = (int) Math.max(1L, Math.min(remaining, PANIC_INTERVAL_TICKS));
		world.scheduleBlockTick(pos, this, step);
		if (remaining > step || now - be.lastPanicScan() >= PANIC_INTERVAL_TICKS) {
			panicNearbyMobs(world, pos, be);
			be.notePanicScan(now, 0);
		}
	}

	/** Starts the vanilla tick chain for a freshly armed device. */
	public void startTimerChain(ServerWorld world, BlockPos pos, int timerTicks) {
		world.scheduleBlockTick(pos, this,
			Math.max(1, Math.min(timerTicks, PANIC_INTERVAL_TICKS)));
	}

	/**
	 * Throttled mob reaction. Bounded three ways — fixed radius, fixed entity count, fixed
	 * interval — so an armed device can never become an entity-scan storm no matter how many
	 * are placed at spawn.
	 */
	public void panicNearbyMobs(ServerWorld world, BlockPos pos, NukeBlockEntity be) {
		DoomsdayConfig c = ConfigManager.get();
		if (c.mobPanicRadius <= 0.0D || c.mobReactionMaxEntities <= 0) {
			return;
		}
		double r = c.mobPanicRadius;
		Vec3d centre = Vec3d.ofCenter(pos);
		Box box = new Box(centre.x - r, centre.y - r, centre.z - r,
			centre.x + r, centre.y + r * 0.6D, centre.z + r);
		List<LivingEntity> found = world.getEntitiesByClass(LivingEntity.class, box,
			e -> e.isAlive() && !e.isRemoved() && !(e instanceof PlayerEntity));
		int limit = Math.min(found.size(), Math.max(0, c.mobReactionMaxEntities));
		int panicked = 0;
		for (int i = 0; i < limit; i++) {
			LivingEntity e = found.get(i);
			// Look at the device first: one vector, no pathfinding, and it reads as
			// "something is very wrong" from any camera angle.
			if (e instanceof MobEntity m) {
				// LookControl lives on MobEntity, not on every LivingEntity.
				m.getLookControl().lookAt(centre.x, centre.y + 0.6D, centre.z);
			}
			if (e.squaredDistanceTo(centre) <= r * r) {
				if (e instanceof MobEntity mob) {
					// Clearing aggro is what turns a mob that was walking *towards* the player
					// into one that runs; leaving it set makes the two systems fight.
					mob.setTarget(null);
					mob.setAttacker(null);
				}
				Vec3d away = e.getPos().subtract(centre);
				double flat = Math.sqrt(away.x * away.x + away.z * away.z);
				if (flat < 1.0E-3D) {
					Random rand = world.getRandom();
					double a = rand.nextFloat() * (float) (Math.PI * 2.0D);
					away = new Vec3d(Math.cos(a), 0.0D, Math.sin(a));
					flat = 1.0D;
				}
				double speed = e instanceof VillagerEntity ? 0.42D : 0.26D;
				e.setVelocity(e.getVelocity().add(
					away.x / flat * speed, 0.18D, away.z / flat * speed));
				e.velocityModified = true;
				panicked++;
			}
		}
		if (be != null) {
			be.notePanicScan(world.getTime(), panicked);
		}
	}
}
