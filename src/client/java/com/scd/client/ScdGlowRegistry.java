package com.scd.client;

import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Registry of "does this entity get a custom glow color right now" checks,
 * consulted once per entity per frame by ScdEntityRendererMixin. Mirrors the
 * adder-list pattern Skyblocker/SkyHanni/CaribouStonks each converge on
 * independently (MobGlowAdder / RenderLivingEntityHelper / EntityGlowComponent)
 * - a list of small strategies instead of one big if-chain, so a new glow
 * feature is just one more registered function, not an edit to this class.
 * First adder to return non-null wins; order only matters if two features
 * could ever claim the same entity at once.
 */
public final class ScdGlowRegistry {
	private static final List<Function<Entity, Integer>> ADDERS = new ArrayList<>();

	private ScdGlowRegistry() {
	}

	public static void register(Function<Entity, Integer> adder) {
		ADDERS.add(adder);
	}

	/** The ARGB color the first matching adder wants for this entity, or null for no custom glow. */
	public static Integer colorForOrNull(Entity entity) {
		for (Function<Entity, Integer> adder : ADDERS) {
			Integer color = adder.apply(entity);
			if (color != null) return color;
		}
		return null;
	}
}
