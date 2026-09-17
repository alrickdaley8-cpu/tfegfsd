package com.doomsday.nukes.util;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;

import java.util.function.Consumer;

/**
 * Stack persistence, one seam.
 *
 * <h2>Why this exists</h2>
 * 1.20.5 removed {@code ItemStack#getNbt()}: a stack's data is now a component map, and free-form
 * NBT lives in the single {@link DataComponentTypes#CUSTOM_DATA} component. Every item in this
 * mod writes through here instead of touching that component directly, for three reasons:
 *
 * <ul>
 *   <li><b>Copy semantics are explicit.</b> {@link NbtComponent#copyNbt()} hands out a copy, so a
 *       read can never mutate a stack, and a write can never half-mutate one. The two helpers below
 *       make that the only available behaviour rather than a rule to remember.</li>
 *   <li><b>Empty means absent.</b> Writing an empty compound removes the component, so an item that
 *       has been "cleared" stops comparing as modified — which is what makes a reset detonator stack
 *       match a fresh one in an anvil/repair context.</li>
 *   <li><b>One place to port.</b> When the component API moves again, this file moves.</li>
 * </ul>
 *
 * <p>Keys are the same short strings the pre-1.20.5 code used, so existing worlds keep their data:
 * the custom-data component is serialised as {@code minecraft:custom_data} containing exactly the
 * compound these helpers write.</p>
 */
public final class StackData {
	private StackData() {
	}

	/**
	 * The stack's free-form data, or an empty compound when it has none. Never returns null, so
	 * callers cannot forget the null check — the single most common source of crash-on-pickup bugs
	 * in items that store state.
	 */
	public static NbtCompound read(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return new NbtCompound();
		}
		NbtComponent component = stack.getOrDefault(DataComponentTypes.CUSTOM_DATA,
			NbtComponent.DEFAULT);
		NbtCompound nbt = component.copyNbt();
		return nbt != null ? nbt : new NbtCompound();
	}

	/**
	 * Mutates the stack's data in place. This is the preferred write path: the component is created
	 * if it is missing and dropped again if the result is empty, and the stack is only touched once.
	 */
	public static void edit(ItemStack stack, Consumer<NbtCompound> mutator) {
		if (stack == null || stack.isEmpty() || mutator == null) {
			return;
		}
		NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, mutator);
	}

	/** Replaces the stack's data wholesale; an empty compound clears the component. */
	public static void write(ItemStack stack, NbtCompound nbt) {
		if (stack == null || stack.isEmpty()) {
			return;
		}
		if (nbt == null || nbt.isEmpty()) {
			stack.remove(DataComponentTypes.CUSTOM_DATA);
			return;
		}
		stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
	}

	/** Convenience for the frequent "read one value, defensively typed" case. */
	public static boolean getBoolean(ItemStack stack, String key) {
		return read(stack).getBoolean(key);
	}

	public static int getInt(ItemStack stack, String key, int fallback) {
		NbtCompound nbt = read(stack);
		return nbt.contains(key) ? nbt.getInt(key) : fallback;
	}

	public static String getString(ItemStack stack, String key) {
		NbtCompound nbt = read(stack);
		return nbt.contains(key) ? nbt.getString(key) : "";
	}
}
