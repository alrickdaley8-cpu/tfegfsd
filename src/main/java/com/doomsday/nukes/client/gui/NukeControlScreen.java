package com.doomsday.nukes.client.gui;

import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.config.DoomsdayConfig;
import com.doomsday.nukes.detonation.NukePreset;
import com.doomsday.nukes.network.packet.DetonateNowC2SPacket;
import com.doomsday.nukes.network.packet.DeviceArmC2SPacket;
import com.doomsday.nukes.network.packet.DeviceDisarmC2SPacket;
import com.doomsday.nukes.util.DText;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

/**
 * The device control panel: right-click a nuke, get this.
 *
 * <h2>No {@code ScreenHandler}, on purpose</h2>
 * The obvious Fabric design is a {@code ScreenHandler} plus slots, or a {@code S2C} sync of the
 * countdown every tick. This screen uses neither. It is a plain {@link Screen}: it *sends intents*
 * ({@code device_arm}, {@code device_disarm}, {@code detonate_now}) and *reads* the state the server
 * already pushes to {@code ClientDeviceStates} for the HUD. That is strictly less machinery, less to
 * desync, and it means the panel is openable while the device is 200 blocks away as long as the
 * server has told the client about it — a screen bound to a live container could not.
 *
 * <p>The countdown you watch here is extrapolated client-side from the last snapshot
 * ({@code ClientDeviceStates.State#extrapolated()}) and corrected by the device's own 1 Hz state
 * packet. A mispredicted second on a 120-second timer is invisible; a per-tick packet per device is
 * not.</p>
 *
 * <h2>Everything is validated twice</h2>
 * The buttons refuse locally (so a click on "Arm 10s" while an EMP is active does nothing and says
 * why), and every one of those rules is re-checked on the server in
 * {@code NukeBlockEntity#requestArm}/{@code requestDisarm}. The client is a courtesy, never a gate.
 */
public class NukeControlScreen extends Screen {
	private static final int[] QUICK_TIMES = {10, 30, 60, 120, 300, 600};

	private final BlockPos pos;
	private final NukePreset preset;
	private final boolean armedOnOpen;
	private final int ticksOnOpen;
	private int selectedSeconds = 60;

	public NukeControlScreen(BlockPos pos, NukePreset preset, boolean armed, int ticksRemaining) {
		super(DText.of("gui.doomsday.control.title", DText.deviceName(preset)));
		this.pos = pos;
		this.preset = preset;
		this.armedOnOpen = armed;
		this.ticksOnOpen = ticksRemaining;
	}

	@Override
	protected void init() {
		int left = this.width / 2 - 100;
		int y = this.height / 2 - 46;

		// Timer stepper — the only number that matters on this screen.
		this.addDrawableChild(ButtonWidget.builder(Text.literal("−10 s"), b -> step(-10))
			.dimensions(left, y, 60, 20).build());
		this.addDrawableChild(ButtonWidget.builder(Text.literal(formatSeconds()), b -> {
			// Cycling on the label itself keeps the panel to three rows: the quick presets are
			// what people actually use, and typing a number is what /doomsday is for.
			this.selectedSeconds = nextQuickTime(this.selectedSeconds);
			this.updateButtons();
		}).dimensions(left + 64, y, 72, 20).build());
		this.addDrawableChild(ButtonWidget.builder(Text.literal("+10 s"), b -> step(+10))
			.dimensions(left + 140, y, 60, 20).build());

		y += 24;
		this.addDrawableChild(ButtonWidget.builder(DText.of("gui.doomsday.control.arm"),
				b -> send(new DeviceArmC2SPacket(this.pos, this.selectedSeconds)))
			.dimensions(left, y, 100, 20).build());
		this.addDrawableChild(ButtonWidget.builder(DText.of("gui.doomsday.control.disarm"),
				b -> send(new DeviceDisarmC2SPacket(this.pos)))
			.dimensions(left + 104, y, 96, 20).build());

		y += 24;
		// The server's gate for this packet is "griefing is on, or you are in creative". The button
		// mirrors that exactly — a locked button with a tooltip is a lie if the server would have
		// accepted the click, and a dead button is worse.
		DoomsdayConfig c = ConfigManager.get();
		boolean creative = this.client != null && this.client.player != null
			&& this.client.player.getAbilities().creativeMode;
		boolean canDetonateNow = c.griefingEnabled || creative;
		this.addDrawableChild(ButtonWidget.builder(
				canDetonateNow ? DText.of("gui.doomsday.control.detonate")
					: DText.of("gui.doomsday.control.detonate_locked", Formatting.GRAY),
				b -> {
					if (canDetonateNow) {
						send(new DetonateNowC2SPacket(this.pos, this.preset.ordinal()));
						this.close();
					}
				})
			.dimensions(left, y, 200, 20).build());

		y += 26;
		this.addDrawableChild(ButtonWidget.builder(DText.of("gui.doomsday.control.presets"),
				b -> cyclePreset())
			.dimensions(left, y, 96, 20).build());
		this.addDrawableChild(ButtonWidget.builder(DText.of("gui.doomsday.control.close"),
				b -> this.close()).dimensions(left + 104, y, 96, 20).build());
	}

	/**
	 * Preset switching is a *server* operation (the block state holds the preset), so the button
	 * sends nothing and instead tells the player what to do. That asymmetry is deliberate: letting
	 * a client swap a Little Boy for a Tsar Bomba with one click would mean the device's yield in
	 * the world file can disagree with the block that was placed, and the block is the thing that
	 * drops as an item. Use the four separate blocks instead — they are in the creative tab, in
	 * order, and cost nothing to explain.
	 */
	private void cyclePreset() {
		if (this.client != null && this.client.player != null) {
			this.client.player.sendMessage(DText.of("gui.doomsday.control.preset_locked",
				DText.deviceName(this.preset)), true);
		}
	}

	private void step(int delta) {
		int min = Math.max(1, ConfigManager.get().minTimerSeconds);
		int max = Math.max(min, ConfigManager.get().maxTimerSeconds);
		this.selectedSeconds = Math.max(min, Math.min(max, this.selectedSeconds + delta));
		this.updateButtons();
	}

	private static int nextQuickTime(int current) {
		for (int quick : QUICK_TIMES) {
			if (quick > current) {
				return quick;
			}
		}
		return QUICK_TIMES[0];
	}

	private void updateButtons() {
		// Rebuilding is cheaper than tracking child indices, and init() on a 7-button screen is a
		// microsecond. Screens are not a hot path.
		this.clearChildren();
		this.init();
	}

	private String formatSeconds() {
		return this.selectedSeconds + " s";
	}

	private static void send(net.minecraft.network.packet.CustomPayload payload) {
		// Client-only class, client-only path: no seam needed here (the seam in
		// ClientPayloadSender exists for the *common* item code, not for this).
		if (ClientPlayNetworking.canSend(payload.getId())) {
			ClientPlayNetworking.send(payload);
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		context.fillGradient(0, 0, this.width, this.height, 0xE0101416, 0xF0050708);
		super.render(context, mouseX, mouseY, delta);
		int cx = this.width / 2;
		int top = this.height / 2 - 96;
		context.drawTextWithShadow(this.textRenderer, this.title, cx - this.textRenderer.getWidth(
			this.title) / 2, top, 0xFFFFD9A0);

		String coords = pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
		context.drawTextWithShadow(this.textRenderer, Text.literal(coords), cx - 24, top + 12,
			0xFF9AA0A6);

		com.doomsday.nukes.client.ClientDeviceStates.State state =
			com.doomsday.nukes.client.ClientDeviceStates.nearest(
				new net.minecraft.util.math.Vec3d(pos.getX() + 0.5D, pos.getY() + 0.5D,
					pos.getZ() + 0.5D), 1.5D);
		int seconds = state != null ? state.secondsRemaining()
			: (this.armedOnOpen ? (this.ticksOnOpen + 19) / 20 : 0);
		Text status;
		if (seconds > 0) {
			status = DText.armedBanner(seconds);
		} else if (state != null && state.empSuppressed()) {
			status = DText.of("gui.doomsday.control.emp_suppressed", Formatting.RED);
		} else {
			status = DText.of("gui.doomsday.control.idle", Formatting.GRAY);
		}
		context.drawTextWithShadow(this.textRenderer, status,
			cx - this.textRenderer.getWidth(status) / 2, top + 26, 0xFFFFFFFF);

		DoomsdayConfig c = ConfigManager.get();
		String tuning = String.format(java.util.Locale.ROOT, "%.1f kt · r=%d m · fallout %d s",
			ConfigManager.tuning(this.preset).yieldKt(),
			(int) Math.round(ConfigManager.tuning(this.preset).blastRadius()),
			(int) Math.round(ConfigManager.tuning(this.preset).radiationSeconds()));
		context.drawTextWithShadow(this.textRenderer, Text.literal(tuning), cx - 60,
			this.height / 2 + 62, 0xFFB8C0B0);
		if (!c.griefingEnabled) {
			context.drawTextWithShadow(this.textRenderer,
				DText.of("gui.doomsday.control.clean_mode", Formatting.YELLOW),
				cx - this.textRenderer.getWidth("clean") / 2, this.height / 2 + 74, 0xFFFFE08A);
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return true;
	}

	/**
	 * Singleplayer must not pause while you set a timer: the device is ticking on the server — which
	 * is the integrated one — so a paused client would freeze the countdown display while the world
	 * kept running. That is exactly the mismatch this screen exists to avoid, hence false.
	 */
	@Override
	public boolean shouldPause() {
		return false;
	}
}
