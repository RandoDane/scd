package com.scd.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists RNG Meter state per Slayer type: the current meter value ("Stored
 * XP"), which drop is currently selected as the guaranteed-at-100% target,
 * and how much meter each specific drop requires (this varies per drop - a
 * rarer selected item needs a much bigger total). Also a running count of
 * bosses killed per (type, tier), the groundwork for an eventual "chance of
 * the drop this run" estimate.
 *
 * The meter value updates from two independent sources: Hypixel sends it
 * directly in chat after every completed quest ("RNG Meter - 1,141,512
 * Stored XP", no menu-reading needed for that), and ScdSlayerMenuWatcher
 * reads it (plus the selected drop and its required total) straight out of
 * the real Slayer menu / "<Boss> RNG Meter" screen.
 *
 * The kill counts alone aren't enough for an accurate probability - that
 * needs Hypixel's real RNG Meter conversion formula, which isn't public.
 * This is deliberately just the raw counting infrastructure, not a
 * fabricated chance number.
 */
public class ScdSlayerRngMeter {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("scd_slayer_rng_meter.json");

	// The wiki's per-boss stat tables show Revenant Horror (Zombie) and Voidgloom Seraph (Enderman)
	// both using this exact same tier -> Slayer XP progression - treated as a universal scaling shared
	// by all types rather than a guess. Low risk either way: this only ever produces an estimate
	// between real readings (chat completion messages, the Slayer menu) - recordStoredXp always
	// overwrites it with the authoritative value whenever one of those arrives, so a wrong number
	// here never persists.
	private static final List<String> TIER_ORDER = List.of("I", "II", "III", "IV", "V");
	private static final Map<String, Long> XP_BY_TIER = Map.of("I", 5L, "II", 25L, "III", 100L, "IV", 500L, "V", 1500L);
	// The RNG Meter tooltip states explicitly it only fills "every time you defeat Voidgloom Seraph
	// III or higher" - assumed the same minimum applies to every type.
	private static final String RNG_METER_MIN_TIER = "III";

	private record SaveData(Map<String, Long> storedXpByType, Map<String, Map<String, Integer>> killsByTypeAndTier,
			Map<String, Double> chancePercentByType, Map<String, String> selectedDropByType,
			Map<String, Map<String, Long>> requiredTotalByTypeAndDrop) {
	}

	private final Map<String, Long> storedXpByType;
	// type name -> tier -> kill count
	private final Map<String, Map<String, Integer>> killsByTypeAndTier;
	// The RNG Meter's own displayed percentage, read straight off the Slayer menu (ScdSlayerMenuWatcher)
	// rather than computed by us - see that class for exactly what's parsed and from where.
	private final Map<String, Double> chancePercentByType;
	// type name -> currently-selected guaranteed-drop item name
	private final Map<String, String> selectedDropByType;
	// type name -> drop item name -> meter total required to guarantee that specific drop
	private final Map<String, Map<String, Long>> requiredTotalByTypeAndDrop;

	private ScdSlayerRngMeter(Map<String, Long> storedXpByType, Map<String, Map<String, Integer>> killsByTypeAndTier,
			Map<String, Double> chancePercentByType, Map<String, String> selectedDropByType,
			Map<String, Map<String, Long>> requiredTotalByTypeAndDrop) {
		this.storedXpByType = storedXpByType;
		this.killsByTypeAndTier = killsByTypeAndTier;
		this.chancePercentByType = chancePercentByType;
		this.selectedDropByType = selectedDropByType;
		this.requiredTotalByTypeAndDrop = requiredTotalByTypeAndDrop;
	}

	public static ScdSlayerRngMeter load() {
		ScdDataMigration.migrateIfNeeded(FabricLoader.getInstance().getConfigDir().resolve("ccbz_slayer_rng_meter.json"), PATH);
		try {
			if (Files.exists(PATH)) {
				String json = Files.readString(PATH);
				SaveData loaded = GSON.fromJson(json, SaveData.class);
				if (loaded != null && loaded.storedXpByType() != null) {
					return new ScdSlayerRngMeter(new HashMap<>(loaded.storedXpByType()),
							loaded.killsByTypeAndTier() != null ? new HashMap<>(loaded.killsByTypeAndTier()) : new HashMap<>(),
							loaded.chancePercentByType() != null ? new HashMap<>(loaded.chancePercentByType()) : new HashMap<>(),
							loaded.selectedDropByType() != null ? new HashMap<>(loaded.selectedDropByType()) : new HashMap<>(),
							loaded.requiredTotalByTypeAndDrop() != null ? new HashMap<>(loaded.requiredTotalByTypeAndDrop()) : new HashMap<>());
				}
				// Pre-existing file from before kill counts were added - just a flat type -> stored XP map.
				Map<String, Long> legacy = GSON.fromJson(json, new TypeToken<Map<String, Long>>() {
				}.getType());
				if (legacy != null) {
					return new ScdSlayerRngMeter(new HashMap<>(legacy), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
				}
			}
		} catch (IOException | RuntimeException e) {
			ScdLog.error("Failed to load RNG meter data from " + PATH + ", starting fresh", e);
		}
		return new ScdSlayerRngMeter(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
	}

	public Long storedXp(ScdSlayerType type) {
		return storedXpByType.get(type.name());
	}

	public void recordStoredXp(ScdSlayerType type, long storedXp) {
		storedXpByType.put(type.name(), storedXp);
		save();
	}

	public Double chancePercent(ScdSlayerType type) {
		return chancePercentByType.get(type.name());
	}

	/** Called by ScdSlayerMenuWatcher whenever it finds a fresh reading in the actual Slayer menu. */
	public void recordChancePercent(ScdSlayerType type, double percent) {
		chancePercentByType.put(type.name(), percent);
		save();
	}

	public String selectedDrop(ScdSlayerType type) {
		return selectedDropByType.get(type.name());
	}

	public void recordSelectedDrop(ScdSlayerType type, String dropName) {
		selectedDropByType.put(type.name(), dropName);
		save();
	}

	/** How much meter the given drop needs to guarantee, if we've ever seen its detail page - null otherwise. */
	public Long requiredTotal(ScdSlayerType type, String dropName) {
		return requiredTotalByTypeAndDrop.getOrDefault(type.name(), Map.of()).get(dropName);
	}

	public void recordRequiredTotal(ScdSlayerType type, String dropName, long total) {
		requiredTotalByTypeAndDrop.computeIfAbsent(type.name(), k -> new HashMap<>()).put(dropName, total);
		save();
	}

	/** Called once per confirmed kill (ScdSlayerBossTracker.Listener.onBossFightEnded). */
	public void recordKill(ScdSlayerType type, String tier) {
		if (tier == null) return;
		var tiers = killsByTypeAndTier.computeIfAbsent(type.name(), k -> new HashMap<>());
		tiers.merge(tier, 1, Integer::sum);
		save();
	}

	/**
	 * Estimates the meter's new value by adding this kill's own XP gain to whatever we last knew, so
	 * the HUD keeps moving between real syncs instead of only updating when the player happens to open
	 * the Slayer menu or complete a full quest. Takes the actual XP gained (base tier XP already
	 * multiplied by any mayor/minister Slayer XP perk, e.g. Aatrox's - see ScdMayorPerks) rather than
	 * looking up XP_BY_TIER itself, so this stays in sync with whatever the session-stats XP counter
	 * shows instead of quietly ignoring the boost. See XP_BY_TIER/RNG_METER_MIN_TIER for where the base
	 * numbers come from and why a small inaccuracy here is low-risk.
	 */
	public void recordKillTowardMeterEstimate(ScdSlayerType type, String tier, long xpGained) {
		if (tier == null) return;
		int have = TIER_ORDER.indexOf(tier);
		int min = TIER_ORDER.indexOf(RNG_METER_MIN_TIER);
		if (have < 0 || have < min) return;

		long current = storedXpByType.getOrDefault(type.name(), 0L);
		recordStoredXp(type, current + xpGained);
	}

	/** The base Slayer XP a kill of this tier awards, before any mayor/minister perk boost - see XP_BY_TIER. Null for an unrecognized tier. */
	public static Long baseSlayerXpForTier(String tier) {
		return XP_BY_TIER.get(tier);
	}

	public int killCount(ScdSlayerType type, String tier) {
		return killsByTypeAndTier.getOrDefault(type.name(), Map.of()).getOrDefault(tier, 0);
	}

	public int totalKillCount(ScdSlayerType type) {
		return killsByTypeAndTier.getOrDefault(type.name(), Map.of()).values().stream().mapToInt(Integer::intValue).sum();
	}

	public void resetKillCounts(ScdSlayerType type) {
		if (killsByTypeAndTier.remove(type.name()) != null) save();
	}

	private void save() {
		try {
			Files.writeString(PATH, GSON.toJson(new SaveData(storedXpByType, killsByTypeAndTier, chancePercentByType,
					selectedDropByType, requiredTotalByTypeAndDrop)));
		} catch (IOException e) {
			ScdLog.error("Failed to save RNG meter data to " + PATH, e);
		}
	}
}
