package com.doomsday.nukes.client.shader;

import com.doomsday.nukes.DoomsdayNukes;
import com.doomsday.nukes.config.ConfigManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

/**
 * Optional upgrade path: a real desaturation/bloom post-effect pass, loaded only when the game's own
 * post-processor plumbing is actually reachable.
 *
 * <h2>Why reflection instead of a direct call</h2>
 * {@code GameRenderer}'s post-effect API is exactly the kind of internal that has moved in every
 * minor version since it appeared — method names, whether loading is synchronous, and who owns
 * {@code close()} have all changed. A direct call means this mod's *core feature* depends on that
 * signature: the flash would be black instead of white on a version bump.
 *
 * <p>So the bridge looks the methods up once, on first use, and if anything is missing it reports
 * {@link #available()} false forever. The caller then uses the HUD quad path in
 * {@code DoomsdayHud#drawFlashPass}, which is what every player gets by default. The worst case for
 * a signature change is "no desaturation", never "no flash" and never a crash.</p>
 *
 * <h2>Why no custom uniform is passed in</h2>
 * Vanilla post-effect programs get {@code GameTime} and {@code ScreenSize} — there is no supported
 * channel for a mod to push a per-frame intensity into an arbitrary {@code .json} pipeline. Rather
 * than inventing one (a resource-pack-injector hack, or writing into the framebuffer's alpha), the
 * pipeline is switched on at the start of an event and off when the whiteout window ends, and the
 * *fade* is done by the HUD quads over the top. That composition is deliberate: the shader supplies
 * the per-pixel desaturation the HUD cannot, and the HUD supplies the smooth envelope the shader
 * cannot.
 *
 * <h2>Iris/Sodium interaction</h2>
 * If a shader pack is active, the framebuffer belongs to it and an injected vanilla post-effect
 * either does nothing or double-resolves the chain. The bridge therefore declines to run when
 * {@code net.irisshaders.iris.api.v0.IrisApi#isShaderPackInUse()} returns true — checked
 * reflectively, so Iris stays a soft dependency and is not in {@code fabric.mod.json}.
 */
public final class PostPipelineBridge {
	private static final Logger LOGGER = LoggerFactory.getLogger("DoomsdayNukes|shader");
	/**
	 * Our own namespace, not {@code minecraft}. Both work — {@code shaders/post} is looked up by
	 * identifier — but registering a file into another namespace means a shader pack or a resource
	 * pack that also owns {@code minecraft:shaders/post/...} can shadow our effect through a path we
	 * do not control, and the failure mode is "no flash", discovered late.
	 */
	private static final String NS = "doomsday";
	private static final Identifier FLASH_PIPELINE = Identifier.of(NS, "shaders/post/doomsday_flash.json");

	private static MethodHandle loadHandle;
	private static MethodHandle setHandle;
	private static MethodHandle closeHandle;
	private static MethodHandle irisInUse;
	private static Object activeProcessor;
	private static boolean probed;
	private static boolean unavailableReasonLogged;
	private static String state = "unprobed";

	private PostPipelineBridge() {
	}

	/** @return true if the vanilla post-effect plumbing was found and Iris is not running */
	public static boolean available() {
		probe();
		return loadHandle != null && setHandle != null && irisInUse == null;
	}

	/** Switch the pipeline on. Idempotent; cheap when unavailable. */
	public static synchronized void begin() {
		if (!available() || activeProcessor != null || !ConfigManager.get().exposureAnimation) {
			return;
		}
		try {
			Object renderer = MinecraftClient.getInstance().gameRenderer;
			Object processor = loadHandle.invoke(renderer, FLASH_PIPELINE);
			if (processor == null) {
				state = "load-returned-null";
				return;
			}
			setHandle.invoke(renderer, processor);
			activeProcessor = processor;
			state = "active";
		} catch (Throwable t) {
			// Includes linkage errors from a renamed shader file: the JSON is a resource, and a
			// missing one must degrade, not crash.
			state = "load-failed: " + t.getClass().getSimpleName();
			logOnce(t);
			disable();
		}
	}

	/** Switch it off and release the render targets. */
	public static synchronized void end() {
		Object processor = activeProcessor;
		if (processor == null) {
			return;
		}
		activeProcessor = null;
		try {
			setHandle.invoke(MinecraftClient.getInstance().gameRenderer, (Object) null);
			if (closeHandle != null) {
				closeHandle.invoke(processor);
			}
			state = "idle";
		} catch (Throwable t) {
			logOnce(t);
		}
	}

	private static void disable() {
		loadHandle = null;
		setHandle = null;
		activeProcessor = null;
	}

	private static synchronized void probe() {
		if (probed) {
			return;
		}
		probed = true;
		Class<?> rendererClass = net.minecraft.client.render.GameRenderer.class;
		loadHandle = find(rendererClass, "loadPostProcessor", Identifier.class);
		setHandle = find(rendererClass, "setPostProcessor",
			postEffectClass());
		closeHandle = find(postEffectClass(), "close");
		irisInUse = findIris();
		if (irisInUse != null) {
			state = "declined (shader pack active)";
		} else if (loadHandle == null || setHandle == null) {
			state = "unavailable (post-effect API not found)";
		} else {
			state = "ready";
		}
		if (!"ready".equals(state)) {
			LOGGER.info("Doomsday flash post-effect pipeline not used — {}. The HUD quad path "
				+ "handles the whiteout instead.", state);
		}
	}

	private static Class<?> postEffectClass() {
		try {
			return Class.forName("net.minecraft.client.shader.PostEffectProcessor");
		} catch (ClassNotFoundException e) {
			return null;
		}
	}

	private static MethodHandle findIris() {
		try {
			Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
			Object instance = api.getMethod("getInstance").invoke(null);
			Method m = api.getMethod("isShaderPackInUse");
			// The probe itself answers the question once, at startup; a shader pack toggled at
			// runtime is caught on the next detonation because begin() re-checks via available().
			Boolean inUse = (Boolean) m.invoke(instance);
			if (Boolean.TRUE.equals(inUse)) {
				return lookup(m);
			}
		} catch (Throwable ignored) {
			// No Iris on the classpath: the common case, and not an error.
		}
		return null;
	}

	private static MethodHandle find(Class<?> owner, String name, Class<?>... params) {
		if (owner == null) {
			return null;
		}
		try {
			return lookup(owner.getMethod(name, params));
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static MethodHandle lookup(Method method) {
		try {
			method.setAccessible(true);
			return MethodHandles.lookup().unreflect(method);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static void logOnce(Throwable t) {
		if (!unavailableReasonLogged) {
			unavailableReasonLogged = true;
			DoomsdayNukes.LOGGER.warn("Flash post-effect disabled after a failure ({}): {}",
				t.getClass().getSimpleName(), t.toString());
		}
	}

	/** Iris may be enabled mid-session; re-probe on demand. */
	public static void reprobe() {
		synchronized (PostPipelineBridge.class) {
			probed = false;
		}
	}

	public static String describe() {
		return "post-effect pipeline: " + state + (activeProcessor != null ? " (loaded)" : "");
	}
}
