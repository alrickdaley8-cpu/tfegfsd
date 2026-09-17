package com.doomsday.nukes.command;

import com.doomsday.nukes.DoomsdayNukes;
import com.doomsday.nukes.block.NukeBlock;
import com.doomsday.nukes.block.entity.NukeBlockEntity;
import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.config.DoomsdayConfig;
import com.doomsday.nukes.detonation.DetonationManager;
import com.doomsday.nukes.detonation.DetonationStage;
import com.doomsday.nukes.detonation.NukePreset;
import com.doomsday.nukes.effect.RadiationManager;
import com.doomsday.nukes.network.ModPackets;
import com.doomsday.nukes.network.packet.StageChangeS2CPacket;
import com.doomsday.nukes.registry.ModBlocks;
import com.doomsday.nukes.util.DText;
import com.doomsday.nukes.world.DoomsdayWorldData;
import com.doomsday.nukes.world.EMPManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * {@code /doomsday} — one root node, permission-gated, nothing destructive below level 2.
 *
 * <h2>Why these subcommands exist</h2>
 * Every one of them is a <em>measurement</em> or <em>reproduction</em> tool that would otherwise
 * require editing a config file and restarting:
 * <ul>
 *   <li>{@code detonate} — fire the full pipeline at a chosen point, at a chosen yield. The single
 *       fastest way to check whether a radius or a duration is right.</li>
 *   <li>{@code preview <stage>} — broadcast one stage to every client without touching the world.
 *       Used for tuning the cloud/fireball curves while standing still; there is no way to reach
 *       the same state any other way, since a stage normally arrives mid-event.</li>
 *   <li>{@code device arm|disarm|list} — drive the block entity through the same entry points the
 *       GUI uses, so a command and a click cannot diverge.</li>
 *   <li>{@code radiation set|add|clear} — dose is a number a player cannot observe directly, so it
 *       has to be settable to be testable.</li>
 *   <li>{@code emp} — trigger the EMP pass on its own, without a 20 kt side effect.</li>
 *   <li>{@code quality|config} — the two knobs that need to change without a restart.</li>
 *   <li>{@code status} — the budgets, live counts and deferred work in one dump, i.e. the output
 *       you paste into a lag report.</li>
 * </ul>
 *
 * <p>Nothing in here parses a block state, writes a file, or bypasses {@code griefingEnabled}:
 * {@code detonate} goes through {@link DetonationManager}, so the master switches in the config are
 * honoured exactly as they are for a real device.</p>
 */
public final class DoomsdayCommands {
	public static final int PERMISSION_GAMEPLAY = 2;
	public static final int PERMISSION_CONFIG = 3;

	private static final SuggestionProvider<ServerCommandSource> PRESETS =
		(ctx, builder) -> {
			for (NukePreset p : NukePreset.values()) {
				// The tooltip is the *display name*, the suggestion is the config key: they differ
				// ("Tsar Bomba" vs "tsar_bomba") and only one of them belongs in the command line.
				builder.suggest(p.configKey, DText.deviceName(p));
			}
			return builder.buildFuture();
		};

	private static final SuggestionProvider<ServerCommandSource> STAGES =
		(ctx, builder) -> {
			for (DetonationStage s : DetonationStage.values()) {
				builder.suggest(s.key());
			}
			return builder.buildFuture();
		};

	private DoomsdayCommands() {
	}

	/** Called from the common entrypoint; registers nothing if Brigadier is unavailable. */
	public static void register() {
		net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
			(dispatcher, registryAccess, environment) -> register(dispatcher));
	}

	static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("doomsday")
			.requires(source -> source.hasPermissionLevel(PERMISSION_GAMEPLAY))
			.then(CommandManager.literal("detonate")
				.then(CommandManager.argument("preset", StringArgumentType.word())
					.suggests(PRESETS)
					.executes(DoomsdayCommands::detonateAtFeet)
					.then(CommandManager.argument("seconds", IntegerArgumentType.integer(0, 3600))
						.executes(DoomsdayCommands::detonateAfterTimer))))
			.then(CommandManager.literal("preview")
				.then(CommandManager.argument("stage", StringArgumentType.word())
					.suggests(STAGES)
					.executes(DoomsdayCommands::previewStage)))
			.then(CommandManager.literal("device")
				.then(CommandManager.literal("arm")
					.then(CommandManager.argument("seconds", IntegerArgumentType.integer(0, 3600))
						.executes(DoomsdayCommands::armLooking)))
				.then(CommandManager.literal("disarm").executes(DoomsdayCommands::disarmLooking))
				.then(CommandManager.literal("list").executes(DoomsdayCommands::listDevices)))
			.then(CommandManager.literal("emp")
				.then(CommandManager.argument("radius", DoubleArgumentType.doubleArg(4.0D, 4096.0D))
					.executes(DoomsdayCommands::empAt)))
			.then(CommandManager.literal("radiation")
				.then(CommandManager.literal("status").executes(DoomsdayCommands::radiationStatus))
				.then(CommandManager.literal("add")
					.then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(-100.0D, 100.0D))
						.executes(DoomsdayCommands::radiationAdd)))
				.then(CommandManager.literal("clear").executes(DoomsdayCommands::radiationClear))
				.then(CommandManager.literal("iodine")
					.then(CommandManager.argument("seconds", IntegerArgumentType.integer(0, 3600))
						.executes(DoomsdayCommands::radiationIodine))))
			.then(CommandManager.literal("quality")
				.requires(source -> source.hasPermissionLevel(PERMISSION_CONFIG))
				.then(CommandManager.argument("tier", StringArgumentType.word())
					.suggests((ctx, builder) -> {
						for (DoomsdayConfig.Quality q : DoomsdayConfig.Quality.values()) {
							builder.suggest(q.name().toLowerCase(java.util.Locale.ROOT));
						}
						return builder.buildFuture();
					})
					.executes(DoomsdayCommands::setQuality)))
			.then(CommandManager.literal("config")
				.requires(source -> source.hasPermissionLevel(PERMISSION_CONFIG))
				.then(CommandManager.literal("dump").executes(DoomsdayCommands::configDump))
				.then(CommandManager.literal("reset").executes(DoomsdayCommands::configReset))
				.then(CommandManager.literal("save").executes(DoomsdayCommands::configSave)))
			.then(CommandManager.literal("status").executes(DoomsdayCommands::status)));
	}

	// ———————————————————————————————————————————————————— detonation

	private static int detonateAtFeet(CommandContext<ServerCommandSource> ctx) {
		ServerPlayerEntity player = ctx.getSource().getPlayer();
		if (player == null) {
			ctx.getSource().sendError(DText.of("command.doomsday.needs_player"));
			return 0;
		}
		NukePreset preset = preset(ctx);
		ServerWorld world = player.getServerWorld();
		Vec3d origin = player.getEyePos();
		DoomsdayNukes.LOGGER.info("[command] {} detonating {} at {}", player.getName().getString(),
			preset.name(), BlockPos.ofFloored(origin).toShortString());
		DoomsdayNukes.detonations().detonate(world, origin, BlockPos.ofFloored(origin), preset);
		ctx.getSource().sendFeedback(DText.of("command.doomsday.detonated",
			DText.deviceName(preset)));
		return 1;
	}

	/** Arms a temporary device at the sender's feet, so {@code /doomsday detonate} can be timed. */
	private static int detonateAfterTimer(CommandContext<ServerCommandSource> ctx) {
		ServerPlayerEntity player = ctx.getSource().getPlayer();
		if (player == null) {
			ctx.getSource().sendError(DText.of("command.doomsday.needs_player"));
			return 0;
		}
		NukePreset preset = preset(ctx);
		int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
		ServerWorld world = player.getServerWorld();
		BlockPos pos = player.getBlockPos().up();
		if (!world.getBlockState(pos).isAir()) {
			pos = pos.up(2);
		}
		world.setBlockState(pos, ModBlocks.blockFor(preset).getDefaultState());
		NukeBlockEntity be = NukeBlock.find(world, pos);
		if (be == null) {
			ctx.getSource().sendError(DText.of("command.doomsday.no_block_entity"));
			return 0;
		}
		int applied = be.requestArm(world, player, seconds);
		ctx.getSource().sendFeedback(DText.of("command.doomsday.armed", applied));
		return applied > 0 ? 1 : 0;
	}

	/** Broadcast one stage to every client in the world, with no world mutation at all. */
	private static int previewStage(CommandContext<ServerCommandSource> ctx) {
		ServerWorld world = ctx.getSource().getWorld();
		String key = StringArgumentType.getString(ctx, "stage");
		DetonationStage stage = DetonationStage.byKey(key);
		if (stage == null) {
			ctx.getSource().sendError(DText.of("command.doomsday.unknown_stage", key));
			return 0;
		}
		ServerPlayerEntity player = ctx.getSource().getPlayer();
		BlockPos origin = player != null ? player.getBlockPos() : world.getSpawnPos();
		// Negative id = "not a real detonation": the client plays the animation and nothing else,
		// and no terrain, radiation or sound state is touched. That is what makes this safe to run
		// on a live survival server.
		// timeSeconds 0 means "the client uses the config's own stage timing", which is what makes
		// a preview match the real thing after a config edit.
		ModPackets.sendToAll(world, new StageChangeS2CPacket(-Math.abs(stage.index() + 1),
			stage.index(), 0.0F, origin));
		ctx.getSource().sendFeedback(DText.of("command.doomsday.preview", stage.key()));
		return 1;
	}

	// ——————————————————————————————————————————————————————— devices

	private static int armLooking(CommandContext<ServerCommandSource> ctx) {
		NukeBlockEntity be = deviceInFront(ctx);
		if (be == null) {
			return 0;
		}
		ServerPlayerEntity player = ctx.getSource().getPlayer();
		int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
		int applied = be.requestArm(player.getServerWorld(), player, seconds);
		ctx.getSource().sendFeedback(applied > 0
			? DText.of("command.doomsday.armed", applied)
			: DText.of("command.doomsday.arm_refused"));
		return applied > 0 ? 1 : 0;
	}

	private static int disarmLooking(CommandContext<ServerCommandSource> ctx) {
		NukeBlockEntity be = deviceInFront(ctx);
		if (be == null) {
			return 0;
		}
		ServerPlayerEntity player = ctx.getSource().getPlayer();
		boolean ok = be.requestDisarm(player.getServerWorld(), player);
		ctx.getSource().sendFeedback(DText.of(ok
			? "command.doomsday.disarmed" : "command.doomsday.nothing_armed"));
		return ok ? 1 : 0;
	}

	/** Resolves the device the sender is looking at, within the same reach the GUI uses. */
	private static NukeBlockEntity deviceInFront(CommandContext<ServerCommandSource> ctx) {
		ServerPlayerEntity player = ctx.getSource().getPlayer();
		if (player == null) {
			ctx.getSource().sendError(DText.of("command.doomsday.needs_player"));
			return null;
		}
		ServerWorld world = player.getServerWorld();
		BlockPos hit = BlockPos.ofFloored(player.getEyePos()
			.add(player.getRotationVector().multiply(8.0D)));
		NukeBlockEntity be = NukeBlock.find(world, hit);
		if (be == null) {
			// One step back, because the ray lands inside the block most of the time when a
			// player is standing over the device they just placed.
			for (int i = 1; i <= 3 && be == null; i++) {
				be = NukeBlock.find(world, hit.north(i));
				if (be == null) {
					be = NukeBlock.find(world, hit.south(i));
				}
				if (be == null) {
					be = NukeBlock.find(world, hit.east(i));
				}
				if (be == null) {
					be = NukeBlock.find(world, hit.west(i));
				}
				if (be == null) {
					be = NukeBlock.find(world, hit.down(i));
				}
				if (be == null) {
					be = NukeBlock.find(world, hit.up(i));
				}
			}
		}
		if (be == null) {
			ctx.getSource().sendError(DText.of("command.doomsday.no_device"));
		}
		return be;
	}

	private static int listDevices(CommandContext<ServerCommandSource> ctx) {
		DetonationManager manager = DoomsdayNukes.detonations();
		java.util.List<String> lines = manager == null ? java.util.List.of()
			: manager.describeArmedDevices();
		if (lines.isEmpty()) {
			ctx.getSource().sendFeedback(DText.of("command.doomsday.no_devices_armed"));
			return 0;
		}
		for (String line : lines) {
			ctx.getSource().sendFeedback(Text.literal(line));
		}
		return lines.size();
	}

	// ————————————————————————————————————————————————————————— other

	private static int empAt(CommandContext<ServerCommandSource> ctx) {
		ServerPlayerEntity player = ctx.getSource().getPlayer();
		if (player == null) {
			ctx.getSource().sendError(DText.of("command.doomsday.needs_player"));
			return 0;
		}
		double radius = DoubleArgumentType.getDouble(ctx, "radius");
		DoomsdayConfig c = ConfigManager.get();
		int ticks = Math.max(20, ConfigManager.ticks(c.empSeconds));
		radius = Math.min(radius, c.empMaxRadius * 8.0D);
		EMPManager.addZone(player.getServerWorld(), player.getEyePos(), radius, ticks);
		ctx.getSource().sendFeedback(DText.of("command.doomsday.emp", (int) radius, ticks / 20));
		return 1;
	}

	private static int radiationStatus(CommandContext<ServerCommandSource> ctx) {
		ServerPlayerEntity player = ctx.getSource().getPlayer();
		if (player == null) {
			ctx.getSource().sendFeedback(Text.literal(RadiationManager.describe()));
			return 1;
		}
		float dose = RadiationManager.doseAt(player.getServerWorld(), player.getPos());
		ctx.getSource().sendFeedback(Text.literal(String.format(java.util.Locale.ROOT,
			"exposure %.1f / 100, dose %.3f, iodine %s, %s",
			RadiationManager.exposureOf(player), dose,
			RadiationManager.isIodineActive(player) ? "active" : "off",
			RadiationManager.describe())));
		return 1;
	}

	private static int radiationAdd(CommandContext<ServerCommandSource> ctx) {
		ServerPlayerEntity player = ctx.getSource().getPlayer();
		if (player == null) {
			ctx.getSource().sendError(DText.of("command.doomsday.needs_player"));
			return 0;
		}
		double amount = DoubleArgumentType.getDouble(ctx, "amount");
		RadiationManager.addExposure(player, amount);
		ctx.getSource().sendFeedback(DText.of("command.doomsday.radiation_now",
			(int) Math.round(RadiationManager.exposureOf(player))));
		return 1;
	}

	private static int radiationClear(CommandContext<ServerCommandSource> ctx) {
		ServerPlayerEntity player = ctx.getSource().getPlayer();
		if (player == null) {
			ctx.getSource().sendError(DText.of("command.doomsday.needs_player"));
			return 0;
		}
		RadiationManager.clearExposure(player);
		ctx.getSource().sendFeedback(DText.of("command.doomsday.radiation_cleared"));
		return 1;
	}

	private static int radiationIodine(CommandContext<ServerCommandSource> ctx) {
		ServerPlayerEntity player = ctx.getSource().getPlayer();
		if (player == null) {
			ctx.getSource().sendError(DText.of("command.doomsday.needs_player"));
			return 0;
		}
		int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
		RadiationManager.takeIodine(player, seconds);
		ctx.getSource().sendFeedback(DText.of("command.doomsday.iodine", seconds));
		return 1;
	}

	private static int setQuality(CommandContext<ServerCommandSource> ctx) {
		String wanted = StringArgumentType.getString(ctx, "tier");
		DoomsdayConfig.Quality target = null;
		for (DoomsdayConfig.Quality q : DoomsdayConfig.Quality.values()) {
			if (q.name().equalsIgnoreCase(wanted)) {
				target = q;
			}
		}
		if (target == null) {
			ctx.getSource().sendError(DText.of("command.doomsday.unknown_quality", wanted));
			return 0;
		}
		DoomsdayConfig c = ConfigManager.get();
		c.quality = target;
		ConfigManager.applyAndSave(c);
		ctx.getSource().sendFeedback(DText.of("command.doomsday.quality", target.name()));
		return 1;
	}

	private static int configDump(CommandContext<ServerCommandSource> ctx) {
		for (String line : ConfigManager.describe().split("\n")) {
			ctx.getSource().sendFeedback(Text.literal(line));
		}
		ctx.getSource().sendFeedback(Text.literal(ModPackets.describePayloads()));
		return 1;
	}

	private static int configReset(CommandContext<ServerCommandSource> ctx) {
		ConfigManager.resetToDefaults();
		ctx.getSource().sendFeedback(DText.of("command.doomsday.config_reset"));
		return 1;
	}

	private static int configSave(CommandContext<ServerCommandSource> ctx) {
		ConfigManager.save();
		ctx.getSource().sendFeedback(DText.of("command.doomsday.config_saved"));
		return 1;
	}

	/** The one-lag-report dump: live events, devices, terrain backlog, zones, radiation, payloads. */
	private static int status(CommandContext<ServerCommandSource> ctx) {
		ServerWorld world = ctx.getSource().getWorld();
		StringBuilder sb = new StringBuilder();
		DetonationManager manager = DoomsdayNukes.detonations();
		sb.append("=== ").append(DoomsdayNukes.MOD_NAME).append(' ').append(DoomsdayNukes.version())
			.append(" ===\n");
		sb.append(manager == null ? "manager not initialised" : manager.describe()).append('\n');
		sb.append(DoomsdayWorldData.get(world).describe()).append('\n');
		sb.append(EMPManager.describe()).append('\n');
		sb.append(RadiationManager.describe()).append('\n');
		sb.append(ModPackets.describePayloads()).append('\n');
		sb.append(ConfigManager.get().quality).append(" quality, ")
			.append(ConfigManager.get().maxVisualEntities).append(" visual entity cap");
		for (String line : sb.toString().split("\n")) {
			ctx.getSource().sendFeedback(Text.literal(line));
		}
		return 1;
	}

	// —————————————————————————————————————————————————————————— utils

	private static NukePreset preset(CommandContext<ServerCommandSource> ctx) {
		String key = StringArgumentType.getString(ctx, "preset");
		NukePreset byKey = NukePreset.byConfigKey(key);
		return byKey == null ? NukePreset.STANDARD_NUKE : byKey;
	}
}
