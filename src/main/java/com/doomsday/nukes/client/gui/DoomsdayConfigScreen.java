package com.doomsday.nukes.client.gui;

import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.config.DoomsdayConfig;
import com.doomsday.nukes.util.DText;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * The mod's own config screen, reachable from Mod Menu and from nothing else.
 *
 * <h2>Why hand-rolled instead of Cloth Config</h2>
 * Cloth is on the compile classpath (it is the ecosystem default) but it is <b>not</b> a required
 * dependency, and an {@code AutoConfigScreenFactory} would crash on launch for anyone who does not
 * have it installed — the exact failure mode a "works everywhere" mod must not have. So this screen
 * depends on nothing but {@link ButtonWidget}, and the two-column layout is 16 rows of the knobs
 * that actually change how the mod feels. The full config is still Gson in
 * {@code config/doomsday.json}, and every value here writes through
 * {@link ConfigManager#applyAndSave} so there is no second source of truth.
 *
 * <h2>Why steppers instead of sliders</h2>
 * {@code SliderWidget}'s construction API changed shape around this exact version (builder + record
 * narration in 1.21.2, plain subclass before), and a config screen is not worth pinning the mod to
 * one of those forms. A stepper is also <em>better</em> here: the values that matter are quantised
 * (0.25/0.5/0.85/1.0 density, whole seconds for timers), and dragging a slider to 0.837 is a
 * worse experience than pressing a button twice.
 *
 * <h2>Every row validates</h2>
 * Changes go through {@code ConfigManager.applyAndSave}, which runs the same clamping the loader
 * runs. There is no "invalid value" state to reach from this screen, which is why there is no
 * error display: a config screen that can create a broken config is worse than one with fewer knobs.
 */
public class DoomsdayConfigScreen extends Screen {
	private static final int ROW_HEIGHT = 20;
	private static final int GAP = 4;
	private static final int WIDTH = 150;

	private final Screen parent;
	private DoomsdayConfig config;

	public DoomsdayConfigScreen(Screen parent) {
		super(DText.of("gui.doomsday.config.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.config = ConfigManager.get();
		int rows = 8;
		int left = this.width / 2 - (WIDTH * 2 + GAP) / 2;
		int top = this.height / 2 - (rows * (ROW_HEIGHT + GAP)) / 2 + 6;

		// Column 1 — what the mod does to the world.
		int y = top;
		toggle(left, y, "gui.doomsday.config.griefing",
			() -> config.griefingEnabled, v -> config.griefingEnabled = v);
		y += ROW_HEIGHT + GAP;
		toggle(left, y, "gui.doomsday.config.flash",
			() -> config.flashEnabled, v -> config.flashEnabled = v);
		y += ROW_HEIGHT + GAP;
		toggle(left, y, "gui.doomsday.config.clouds",
			() -> config.cloudEnabled, v -> config.cloudEnabled = v);
		y += ROW_HEIGHT + GAP;
		toggle(left, y, "gui.doomsday.config.fallout",
			() -> config.falloutEnabled, v -> config.falloutEnabled = v);
		y += ROW_HEIGHT + GAP;
		toggle(left, y, "gui.doomsday.config.radiation",
			() -> config.radiationEnabled, v -> config.radiationEnabled = v);
		y += ROW_HEIGHT + GAP;
		toggle(left, y, "gui.doomsday.config.atmosphere",
			() -> config.atmosphericAftermath, v -> config.atmosphericAftermath = v);
		y += ROW_HEIGHT + GAP;
		number(left, y, "gui.doomsday.config.terrain_budget",
			() -> config.terrainBlocksPerTick, v -> config.terrainBlocksPerTick = (int) v,
			100.0D, 5000.0D, 100.0D, 0);
		y += ROW_HEIGHT + GAP;
		number(left, y, "gui.doomsday.config.max_entities",
			() -> config.maxVisualEntities, v -> config.maxVisualEntities = (int) v,
			4.0D, 96.0D, 4.0D, 0);

		// Column 2 — how it looks and how loud it is.
		int x2 = left + WIDTH + GAP;
		int y2 = top;
		// The quality label is translated per value rather than printed as the enum name, so the
		// button reads "Ultra" and not "ULTRA" — and so a translation can reorder the words.
		cycle(x2, y2, "gui.doomsday.config.quality",
			() -> DText.of("gui.doomsday.config.quality." + config.quality.name()).getString(),
			() -> config.quality = next(config.quality));
		y2 += ROW_HEIGHT + GAP;
		number(x2, y2, "gui.doomsday.config.density",
			() -> config.particleDensity, v -> config.particleDensity = v,
			0.25D, 1.0D, 0.15D, 2);
		y2 += ROW_HEIGHT + GAP;
		toggle(x2, y2, "gui.doomsday.config.adaptive",
			() -> config.adaptiveQuality, v -> config.adaptiveQuality = v);
		y2 += ROW_HEIGHT + GAP;
		toggle(x2, y2, "gui.doomsday.config.fov",
			() -> config.fovPunchEnabled, v -> config.fovPunchEnabled = v);
		y2 += ROW_HEIGHT + GAP;
		number(x2, y2, "gui.doomsday.config.sound_distance",
			() -> config.soundDistance, v -> config.soundDistance = v,
			32.0D, 4096.0D, 32.0D, 0);
		y2 += ROW_HEIGHT + GAP;
		number(x2, y2, "gui.doomsday.config.timer",
			() -> config.defaultTimerSeconds, v -> config.defaultTimerSeconds = (int) v,
			1.0D, 3600.0D, 15.0D, 0);
		y2 += ROW_HEIGHT + GAP;
		toggle(x2, y2, "gui.doomsday.config.remote_disarm",
			() -> config.remoteCanDisarm, v -> config.remoteCanDisarm = v);
		y2 += ROW_HEIGHT + GAP;
		toggle(x2, y2, "gui.doomsday.config.verbose",
			() -> config.verboseLogging, v -> config.verboseLogging = v);

		// Footer.
		int fy = top + rows * (ROW_HEIGHT + GAP) + 6;
		this.addDrawableChild(ButtonWidget.builder(DText.of("gui.doomsday.config.save"), b -> {
			ConfigManager.applyAndSave(this.config);
			this.close();
		}).dimensions(left, fy, WIDTH, 20).build());
		this.addDrawableChild(ButtonWidget.builder(DText.of("gui.doomsday.config.reset"), b -> {
			ConfigManager.resetToDefaults();
			this.config = ConfigManager.get();
			this.clearChildren();
			this.init();
		}).dimensions(left + WIDTH + GAP, fy, WIDTH, 20).build());
		this.addDrawableChild(ButtonWidget.builder(DText.of("gui.doomsday.config.cancel"), b -> {
			// Discard: re-read from disk so nothing the player undid can leak into the session.
			ConfigManager.load();
			this.config = ConfigManager.get();
			this.close();
		}).dimensions(left, fy + 24, WIDTH * 2 + GAP, 20).build());
	}

	/**
	 * Quality cycle order. Written as an explicit successor instead of {@code ordinal() + 1} so that
	 * inserting a tier later (or a config that pinned an ordinal) cannot silently jump a user from
	 * ULTRA to LOW.
	 */
	private static DoomsdayConfig.Quality next(DoomsdayConfig.Quality q) {
		return switch (q) {
			case LOW -> DoomsdayConfig.Quality.MEDIUM;
			case MEDIUM -> DoomsdayConfig.Quality.HIGH;
			case HIGH, ULTRA -> DoomsdayConfig.Quality.ULTRA;
		};
	}

	private void toggle(int x, int y, String key, BooleanSupplier get, Consumer<Boolean> set) {
		this.addDrawableChild(ButtonWidget.builder(label(key, get.getAsBoolean() ? "ON" : "OFF",
				get.getAsBoolean() ? Formatting.GREEN : Formatting.GRAY), b -> {
			set.accept(!get.getAsBoolean());
			this.clearChildren();
			this.init();
		}).dimensions(x, y, WIDTH, ROW_HEIGHT).build());
	}

	private void number(int x, int y, String key, DoubleSupplier get, DoubleConsumer set,
						double min, double max, double step, int decimals) {
		// The button body increments, the small box on its right edge decrements: two hot spots, one
		// widget per value, and no slider to pin a version on.
		this.addDrawableChild(ButtonWidget.builder(text(key, get.getAsDouble(), decimals), b -> {
			set.accept(clampTo(get.getAsDouble() + step, min, max));
			this.clearChildren();
			this.init();
		}).dimensions(x, y, WIDTH, ROW_HEIGHT).build());
		this.addDrawableChild(ButtonWidget.builder(Text.literal("−"), b -> {
			set.accept(clampTo(get.getAsDouble() - step, min, max));
			this.clearChildren();
			this.init();
		}).dimensions(x + WIDTH - 18, y + 2, 16, ROW_HEIGHT - 4).build());
	}

	private static double clampTo(double v, double min, double max) {
		return Math.max(min, Math.min(max, v));
	}

	private void cycle(int x, int y, String key, java.util.function.Supplier<String> value,
					   Runnable advance) {
		this.addDrawableChild(ButtonWidget.builder(label(key, value.get(), Formatting.WHITE), b -> {
			advance.run();
			this.clearChildren();
			this.init();
		}).dimensions(x, y, WIDTH, ROW_HEIGHT).build());
	}

	private Text text(String key, double value, int decimals) {
		String num = decimals <= 0 ? String.valueOf((long) value)
			: String.format(java.util.Locale.ROOT, "%." + decimals + "f", value);
		return label(key, num, Formatting.WHITE);
	}

	// Formatting, not a raw RGB int: Text is immutable in 1.21.1 and the mutation surface is
	// MutableText, so a "just tint the value" overload has to be built on formatted() anyway.
	private static Text label(String key, String value, Formatting color) {
		return DText.mutable(key).append(Text.literal(": "))
			.append(Text.literal(value).formatted(color));
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		context.fillGradient(0, 0, this.width, this.height, 0xE00C1010, 0xF0060909);
		super.render(context, mouseX, mouseY, delta);
		context.drawTextWithShadow(this.textRenderer, this.title,
			this.width / 2 - this.textRenderer.getWidth(this.title) / 2, this.height / 2 - 108,
			0xFFFFD9A0);
		Text hint = DText.of("gui.doomsday.config.hint", Formatting.GRAY);
		context.drawTextWithShadow(this.textRenderer, hint,
			this.width / 2 - this.textRenderer.getWidth(hint) / 2, this.height - 30, 0xFFAAAAAA);
	}

	@Override
	public void close() {
		if (this.client != null) {
			this.client.setScreen(this.parent);
		}
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
