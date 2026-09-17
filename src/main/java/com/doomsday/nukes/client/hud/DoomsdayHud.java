package com.doomsday.nukes.client.hud;

import com.doomsday.nukes.client.ClientDetonationState;
import com.doomsday.nukes.client.ClientDeviceStates;
import com.doomsday.nukes.client.GeigerDriver;
import com.doomsday.nukes.config.ConfigManager;
import com.doomsday.nukes.config.DoomsdayConfig;
import com.doomsday.nukes.effect.RadiationManager;
import com.doomsday.nukes.item.HazmatGear;
import com.doomsday.nukes.util.DText;
import com.doomsday.nukes.util.MathUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

/**
 * The mod's HUD: dosimeter, protection state, device banner, and the two full-screen passes that a
 * shader cannot be trusted to provide on every setup.
 *
 * <h2>Registered through {@code HudRenderCallback}, never by subclassing {@code InGameHud}</h2>
 * A mixin into {@code InGameHud.render} would draw over the scoreboard/boss bars depending on
 * injection order and would break on any HUD-mod that also injects. {@code HudRenderCallback} runs
 * after vanilla's own HUD pass, which is the layer this belongs in: it is information, not chrome.
 *
 * <h2>Layout</h2>
 * Bottom-left, above the hotbar, in the same "instrument cluster" position as the compass/experience
 * row — deliberately away from the health/hunger stack so the eye can learn one place. Everything is
 * scaled to the GUI scale the player already chose; nothing here assumes a resolution, and the
 * numbers are drawn with the game's own text renderer so they follow the resource pack's font.
 *
 * <h2>Zero-cost when idle</h2>
 * Every element is gated behind a cheap boolean; with no device armed, no dose and no live event,
 * this class returns from {@link #render} after two field reads and draws nothing at all.
 */
public final class DoomsdayHud {
	/** Width of the dose bar, in GUI pixels. */
	private static final int BAR_WIDTH = 84;
	private static final int BAR_HEIGHT = 4;

	private DoomsdayHud() {
	}

	public static void render(DrawContext context, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.player == null || client.options == null
			|| client.options.hudHidden) {
			return;
		}
		DoomsdayConfig c = ConfigManager.get();
		int screenWidth = context.getScaledWindowWidth();
		int screenHeight = context.getScaledWindowHeight();

		// 1 — full-screen flash pass (see the class javadoc for why this is a quad, not a shader)
		ClientDetonationState state = ClientDetonationState.get();
		if (state.isActive()) {
			drawFlashPass(context, screenWidth, screenHeight, state, c);
		}

		// 2 — goggled lens frame
		if (HazmatGear.indicatorActive(client.player)) {
			drawGoggleFrame(context, screenWidth, screenHeight, tickDelta);
		}

		// 3 — armed device banner
		Vec3d eye = client.player.getEyePos();
		ClientDeviceStates.State device = ClientDeviceStates.nearestArmed(eye,
			Math.max(16.0D, c.geigerRange));
		if (device != null) {
			drawDeviceBanner(context, screenWidth, device);
		}

		// 4 — dosimeter cluster
		float dose = RadiationManager.clientDose();
		double exposure = RadiationManager.clientExposure();
		boolean holdingCounter = counterHeld(client);
		if (dose > 0.001F || exposure > 1.0D || holdingCounter) {
			drawDosimeter(context, screenWidth, screenHeight, dose, exposure, holdingCounter,
				RadiationManager.clientIodineActive());
		}
	}

	/**
	 * The text renderer. DrawContext exposes no accessor of its own in 1.21.1 — the HUD is handed a
	 * context and a tick counter, so the client instance is the only route to the font. Null-guarded:
	 * a HUD callback can fire once during a world change, and returning null here makes every call
	 * site a no-op instead of a crash.
	 */
	private static net.minecraft.client.font.TextRenderer clientText() {
		MinecraftClient client = MinecraftClient.getInstance();
		return client == null ? null : client.textRenderer;
	}

	private static boolean counterHeld(MinecraftClient client) {
		return client.player != null
			&& com.doomsday.nukes.item.GeigerCounterItem.isHeld(client.player);
	}

	// ————————————————————————————————————————————————————— elements

	/**
	 * Whiteout + haze, drawn as two full-screen quads with straight alpha.
	 *
	 * <p>This is the fallback path and, in practice, the path everybody gets: a real
	 * desaturation/bloom post-process needs a render target and a shader that has to survive Iris,
	 * Sodium, and whatever GUI mod is installed. The quad gets 95 % of the effect — the screen goes
	 * white, then the world comes back through an orange haze — for no compatibility risk at all.
	 * {@code PostPipelineBridge} upgrades this when a shader is genuinely available; it never
	 * depends on one.</p>
	 */
	private static void drawFlashPass(DrawContext context, int width, int height,
									  ClientDetonationState state, DoomsdayConfig c) {
		float whiteout = MathUtil.clamp01(state.flashWhiteout());
		if (whiteout > 0.002F) {
			int alpha = (int) (whiteout * 255.0F);
			int argb = (alpha << 24) | 0x00FFFFFF;
			// Fill, not gradient: a whiteout has to be uniform or it reads as a bug.
			context.fill(0, 0, width, height, argb);
		}
		float haze = MathUtil.clamp01(state.hazeFog());
		float orange = state.orangeMix();
		if (haze > 0.002F) {
			int r = (int) (110.0F + 90.0F * orange);
			int g = (int) (86.0F + 34.0F * orange);
			int b = (int) (54.0F - 24.0F * orange);
			int alpha = (int) (haze * 150.0F * (0.35F + 0.65F * (c.vignette ? 1.0F : 0.6F)));
			context.fillGradient(0, 0, width, height,
				(alpha << 24) | (r << 16) | (g << 8) | b,
				((alpha / 2) << 24) | (r << 16) | (g << 8) | b);
		}
		if (c.vignette && (haze > 0.02F || whiteout > 0.02F)) {
			drawVignette(context, width, height, Math.max(haze, whiteout) * (float) c.vignetteStrength);
		}
	}

	private static void drawVignette(DrawContext context, int width, int height, float strength) {
		int band = Math.max(8, (int) (Math.min(width, height) * 0.16F));
		int alpha = (int) (MathUtil.clamp01(strength) * 165.0F);
		if (alpha <= 2) {
			return;
		}
		int dark = (alpha << 24);
		int clear = 0x00000000;
		context.fillGradient(0, 0, width, band, dark, clear);
		context.fillGradient(0, height - band, width, height, clear, dark);
		context.fillGradient(0, band, band, height - band, dark, clear);
		context.fillGradient(width - band, band, width, height - band, clear, dark);
	}

	/** A soft dark frame plus two lens circles: the "you are wearing something" cue. */
	private static void drawGoggleFrame(DrawContext context, int width, int height,
										float tickDelta) {
		int r = (int) (Math.min(width, height) * 0.34F);
		int cx = width / 2;
		int cy = height / 2;
		int thickness = Math.max(6, (int) (Math.min(width, height) * 0.05F));
		int tint = 0x66101A08;
		// Four bars rather than a scissored ring: cheaper, and it composes correctly with the
		// vanilla hotbar and crosshair instead of overdrawning them.
		context.fill(0, 0, width, thickness, tint);
		context.fill(0, height - thickness, width, height, tint);
		context.fill(0, 0, thickness, height, tint);
		context.fill(width - thickness, 0, width, height, tint);
		// Lens hint: two subtle darkened squares where the eyepieces would be.
		int lens = (int) (r * 0.62F);
		int lensTint = 0x22000000;
		context.fill(cx - lens - lens / 3, cy - lens / 2, cx - lens / 3, cy + lens / 2, lensTint);
		context.fill(cx + lens / 3, cy - lens / 2, cx + lens + lens / 3, cy + lens / 2, lensTint);
	}

	private static void drawDeviceBanner(DrawContext context, int width,
										 ClientDeviceStates.State device) {
		int seconds = device.secondsRemaining();
		Text banner = DText.armedBanner(seconds);
		if (device.empSuppressed()) {
			banner = banner.shallowCopy().formatted(Formatting.GRAY);
		}
		int textWidth = clientText().getWidth(banner);
		int x = (width - textWidth) / 2;
		int y = 12;
		context.fill(x - 5, y - 3, x + textWidth + 5, y + 13, 0xB0100A0A);
		context.drawTextWithShadow(clientText(), banner, x, y, 0xFFFFCCAA);
	}

	private static void drawDosimeter(DrawContext context, int width, int height, float dose,
									  double exposure, boolean holdingCounter, boolean iodine) {
		int x = 8;
		int y = height - 42;
		if (!holdingCounter) {
			// Lift it slightly when the player is not holding the instrument: without a counter
			// this is a warning strip, not an instrument panel, and it must not compete with the
			// hotbar's own selection highlight.
			y -= 6;
		}

		// Dose bar (environment) and exposure bar (body) share one frame: two numbers, one glance.
		float doseFill = MathUtil.clamp01(dose);
		float exposureFill = MathUtil.clamp01((float) (exposure / 100.0D));
		context.fill(x - 2, y - 2, x + BAR_WIDTH + 2, y + BAR_HEIGHT * 2 + 6, 0xA0000000);
		bar(context, x, y, doseFill, 0xFF9BF04A, 0xFF3C7A12);
		bar(context, x, y + BAR_HEIGHT + 2, exposureFill, 0xFFF0E04A, 0xFF7A5A12);

		String label = String.format(java.util.Locale.ROOT, "%d%% / %d",
			(int) (doseFill * 100.0F), (int) Math.round(exposure));
		context.drawTextWithShadow(clientText(), Text.literal(label),
			x + BAR_WIDTH + 6, y, 0xFFDDDDDD);
		if (iodine) {
			context.drawTextWithShadow(clientText(),
				DText.of("gui.doomsday.radiation.iodine_active"), x + BAR_WIDTH + 6, y + 10,
				0xFF9BE8FF);
		}
		if (!holdingCounter) {
			context.drawTextWithShadow(clientText(),
				DText.of("gui.doomsday.geiger.hint"), x, y + BAR_HEIGHT * 2 + 9, 0xFFAAAAAA);
		}
		// Needle value from the driver, not recomputed here: the driver is the only thing that
		// knows about sweep-mode scaling, and a HUD that re-derived it would disagree with the
		// click rate under a config edit mid-second.
		double needle = GeigerDriver.needle();
		if (needle > 0.0005D) {
			int ticks = (int) Math.round(needle * 20.0D);
			context.drawTextWithShadow(clientText(),
				Text.literal("≈" + ticks + " c/s"), x + BAR_WIDTH + 6, y + BAR_HEIGHT + 2,
				0xFFB0B0B0);
		}
	}

	private static void bar(DrawContext context, int x, int y, float fill, int full, int empty) {
		context.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, 0xFF202020);
		if (fill <= 0.0F) {
			return;
		}
		int w = Math.max(1, (int) (fill * BAR_WIDTH));
		// Two-tone: the leading edge is brighter, so a rising bar is legible even at 4 px tall.
		context.fill(x, y, x + w, y + BAR_HEIGHT, empty);
		context.fill(x, y, x + w, y + 1, full);
	}

	/** Goggle durability readout drawn next to the item itself, by the caller's HUD slot. */
	public static Text protectionSummary(ItemStack goggles) {
		int damage = goggles.getDamage();
		int max = goggles.getMaxDamage();
		int left = Math.max(0, max - damage);
		return DText.of("gui.doomsday.hazmat.status",
			(int) Math.round(100.0D * left / Math.max(1, max)));
	}
}
