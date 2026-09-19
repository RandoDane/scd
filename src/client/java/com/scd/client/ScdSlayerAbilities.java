package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-boss ability call-outs for the Slayer tracker HUD, sourced from the
 * Hypixel SkyBlock wiki's per-boss pages.
 *
 * Only mechanics with a genuinely reliable trigger are included: a
 * repeating fixed-interval timer, an HP-percentage threshold crossing (via
 * ScdSlayerBossTracker's one-shot alert tracking), or a distinctly-named
 * second entity. Mechanics whose real trigger is a boss animation or
 * particle effect with no detectable signal (Fire Pillar's exact throw,
 * Yang Glyph's exact throw, Killer Spring, Impel, Nukekubi) are deliberately
 * left out rather than faked with a guessed timer.
 */
public final class ScdSlayerAbilities {
	private static final double NEARBY_RADIUS = 30.0;

	private ScdSlayerAbilities() {
	}

	public static List<String> lines(ScdConfig config, ScdSlayerQuest quest, LivingEntity boss, ScdSlayerBossTracker tracker) {
		List<String> lines = new ArrayList<>();
		if (quest == null || boss == null) return lines;

		String tier = quest.tier();
		// hpFrac comes from ScdSlayerHealthReading (parsed off the boss's own nameplate text), not
		// the entity's vanilla health attribute - that's not actually tied to a Slayer boss's real HP
		// pool. Null (nameplate not parseable yet) is treated as full HP so threshold cues below
		// simply don't fire rather than firing wrongly.
		float safeHpFrac = tracker.currentHpFracOrNull() != null ? tracker.currentHpFracOrNull() : 1f;
		long fightElapsedMs = tracker.fightElapsedMs();

		switch (quest.type()) {
			case ZOMBIE -> {
				if (config.slayer.zombie.enrageEnabled && atLeast(tier, "III")) {
					lines.add(repeatingCountdown("Enrage", fightElapsedMs, 40_000));
				}
			}
			case VAMPIRE -> {
				if (config.slayer.vampire.twinclawEnabled && atLeast(tier, "II")) {
					lines.add(repeatingCountdown("Twinclaw", fightElapsedMs, 7_000));
				}
				if (config.slayer.vampire.maniaEnabled) {
					if (atLeast(tier, "III") && (tracker.isAlertActive("vamp_mania_75") || tracker.isAlertActive("vamp_mania_40"))) {
						lines.add("Mania! Stand in the green zone");
					} else if (("I".equals(tier) || "II".equals(tier)) && tracker.isAlertActive("vamp_mania_50")) {
						lines.add("Mania! Stand in the green zone");
					}
				}
			}
			case SPIDER -> {
				if (config.slayer.spider.eggSacEnabled && atLeast(tier, "III")
						&& (tracker.isAlertActive("spider_egg_66") || tracker.isAlertActive("spider_egg_33"))) {
					lines.add("Egg sacs spawning - destroy them");
				}
				if (config.slayer.spider.conjoinedBroodWarningEnabled && "V".equals(tier) && tracker.isAlertActive("spider_conjoined_transition")) {
					lines.add("Not dead yet - Conjoined Brood incoming!");
				}
			}
			case BLAZE -> {
				if (config.slayer.blaze.firePillarEnabled && atLeast(tier, "II") && safeHpFrac <= 0.5f) {
					lines.add("Fire Pillars active - destroy within 7s");
				}
				if (config.slayer.blaze.demonsplitEnabled && hasNamedPair(boss, "Quazii", "Typhoeus")) {
					lines.add("Demonsplit - only one half is damageable");
				}
			}
			case ENDERMAN -> {
				if (config.slayer.enderman.beamPhaseEnabled && "IV".equals(tier)
						&& (tracker.isAlertActive("ender_beam_5_6") || tracker.isAlertActive("ender_beam_1_2") || tracker.isAlertActive("ender_beam_1_6"))) {
					lines.add("Beam phase starting!");
				}
				if (config.slayer.enderman.hitshieldEnabled) {
					var shield = tracker.shieldReadingOrNull();
					if (shield != null && shield.active()) {
						lines.add("Hitshield up" + (shield.hitsRemaining() != null ? " (" + shield.hitsRemaining() + " hits left)" : "") + " - 0 dmg, don't waste hits");
					}
				}
			}
			case WOLF -> {
				if (config.slayer.wolf.callThePupsEnabled && atLeast(tier, "III") && tracker.isAlertActive("wolf_pups")) {
					lines.add("Call the Pups! Boss is protected ~5s");
				}
			}
		}
		return lines;
	}

	private static String repeatingCountdown(String name, long fightElapsedMs, long intervalMs) {
		long remaining = intervalMs - (fightElapsedMs % intervalMs);
		return name + " ~" + (remaining / 1000) + "s";
	}

	private static boolean hasNamedPair(LivingEntity boss, String nameA, String nameB) {
		var mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return false;
		boolean foundA = false, foundB = false;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof LivingEntity living) || entity instanceof ArmorStand || !living.isAlive()) continue;
			if (living.distanceTo(mc.player) > NEARBY_RADIUS) continue;
			if (!living.hasCustomName() || living.getCustomName() == null) continue;
			String name = living.getCustomName().getString();
			if (name.contains(nameA)) foundA = true;
			if (name.contains(nameB)) foundB = true;
		}
		return foundA && foundB;
	}

	private static boolean atLeast(String tier, String minTier) {
		List<String> order = List.of("I", "II", "III", "IV", "V");
		int have = order.indexOf(tier);
		int min = order.indexOf(minTier);
		return have >= 0 && have >= min;
	}
}
