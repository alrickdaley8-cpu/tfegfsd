package com.doomsday.nukes.client;

import com.doomsday.nukes.client.gui.DoomsdayConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Mod Menu entry point: one method, returning one factory.
 *
 * <h2>Optional by construction</h2>
 * Mod Menu is a {@code modCompileOnly} dependency (see {@code build.gradle}) and this class is
 * named only from {@code fabric.mod.json}'s {@code modmenu} entrypoint. If Mod Menu is not installed
 * nothing ever loads the class, so there is no {@code NoClassDefFoundError} to guard against and no
 * "soft dependency" reflection dance. That is also why it is <em>not</em> in {@code requires} — a
 * mod menu is a luxury, and a nuke mod that won't launch without one is a bad mod.
 *
 * <p>Cloth Config is likewise compile-only, and deliberately unused: the config screen is ours
 * (see {@link DoomsdayConfigScreen} for the reasoning). This file exists so that whoever <em>does</em>
 * run Mod Menu gets a "Config" button next to the mod in the list, which is where players look for
 * it.</p>
 */
@Environment(EnvType.CLIENT)
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return DoomsdayConfigScreen::new;
	}
}
