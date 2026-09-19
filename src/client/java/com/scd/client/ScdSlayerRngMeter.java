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
 *
 * <p><b>Daemon Shard ("Pity" attribute) estimation:</b> that shard boosts
 * RNG Meter XP gain by up to 1.10x at max level (10 levels, +0.01x each),
 * with no client-visible signal for which level is actually equipped -
 * there's no reasonable way to read it directly. Instead, every time an
 * authoritative real value arrives (recordStoredXp, from either sync
 * source above), it's compared against the sum of un-boosted "expected"
 * XP this class itself predicted for the kills that happened since the
 * previous authoritative value (see expectedRawXpSinceSyncByType). The
 * ratio of actual-to-expected reveals the extra multiplier the account's
 * Daemon Shard is actually applying, which is then used to make future
 * between-sync estimates more accurate. Deliberately janky (a shard whose
 * level truly cannot be read shouldn't need this at all), but it's the
 * only lever available - explicitly a best-effort estimate, not a
 * guaranteed-correct reading, and it only ever affects the ESTIMATE shown
 * between real syncs; the real synced value itself is never altered.
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

	// Daemon Shard: 10 levels, +0.01x multiplier each, 1.00x-1.10x - used to clamp/snap the estimated
	// multiplier to one of the 11 actually-possible values instead of trusting raw measurement noise.
	private static final double DAEMON_STEP = 0.01;
	private static final int DAEMON_MAX_LEVEL = 10;
	// Below this, a sync window's "expected" sum is too small for the implied-multiplier ratio to be
	// trustworthy (e.g. a single Tier III kill's rounding alone could swing the ratio by a full level).
	private static final long MIN_EXPECTED_XP_TO_TRUST = 2_000L;

	private record SaveData(Map<String, Long> storedXpByType, Map<String, Map<String, Integer>> killsByTypeAndTier,
			Map<String, Double> chancePercentByType, Map<String, String> selectedDropByType,
			Map<String, Map<String, Long>> requiredTotalByTypeAndDrop, Map<String, Long> lastRealSyncXpByType,
			Map<String, Long> expectedRawXpSinceSyncByType, Double daemonMultiplier) {
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
	// type name -> the authoritative stored-XP value at the last real sync (chat message or menu read)
	private final Map<String, Long> lastRealSyncXpByType;
	// type name -> sum of un-boosted (mayor-only) expected XP from kills since that last real sync
	private final Map<String, Long> expectedRawXpSinceSyncByType;
	// 1.0 (no boost) until a real sync reveals otherwise - see the class doc above.
	private double daemonMultiplier;

	private ScdSlayerRngMeter(Map<String, Long> storedXpByType, Map<String, Map<String, Integer>> killsByTypeAndTier,
			Map<String, Double> chancePercentByType, Map<String, String> selectedDropByType,
			Map<String, Map<String, Long>> requiredTotalByTypeAndDrop, Map<String, Long> lastRealSyncXpByType,
			Map<String, Long> expectedRawXpSinceSyncByType, double daemonMultiplier) {
		this.storedXpByType = storedXpByType;
		this.killsByTypeAndTier = killsByTypeAndTier;
		this.chancePercentByType = chancePercentByType;
		this.selectedDropByType = selectedDropByType;
		this.requiredTotalByTypeAndDrop = requiredTotalByTypeAndDrop;
		this.lastRealSyncXpByType = lastRealSyncXpByType;
		this.expectedRawXpSinceSyncByType = expectedRawXpSinceSyncByType;
		this.daemonMultiplier = daemonMultiplier;
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
							loaded.requiredTotalByTypeAndDrop() != null ? new HashMap<>(loaded.requiredTotalByTypeAndDrop()) : new HashMap<>(),
							loaded.lastRealSyncXpByType() != null ? new HashMap<>(loaded.lastRealSyncXpByType()) : new HashMap<>(),
							loaded.expectedRawXpSinceSyncByType() != null ? new HashMap<>(loaded.expectedRawXpSinceSyncByType()) : new HashMap<>(),
							loaded.daemonMultiplier() != null ? loaded.daemonMultiplier() : 1.0);
				}
				// Pre-existing file from before kill counts were added - just a flat type -> stored XP map.
				Map<String, Long> legacy = GSON.fromJson(json, new TypeToken<Map<String, Long>>() {
				}.getType());
				if (legacy != null) {
					return new ScdSlayerRngMeter(new HashMap<>(legacy), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
							new HashMap<>(), new HashMap<>(), 1.0);
				}
			}
		} catch (IOException | RuntimeException e) {
			ScdLog.error("Failed to load RNG meter data from " + PATH + ", starting fresh", e);
		}
		return new ScdSlayerRngMeter(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
				new HashMap<>(), new HashMap<>(), 1.0);
	}

	public Long storedXp(ScdSlayerType type) {
		return storedXpByType.get(type.name());
	}

	/**
	 * Records an authoritative real value from Hypixel itself (chat message or menu read - both
	 * equally trustworthy). Before overwriting, reconciles the Daemon Shard estimate: compares the
	 * real change since the last authoritative value against what recordKillTowardMeterEstimate
	 * predicted (mayor boost only, no Daemon boost) for the kills in between - see the class doc.
	 */
	public void recordStoredXp(ScdSlayerType type, long storedXp) {
		Long previous = lastRealSyncXpByType.get(type.name());
		Long expectedRaw = expectedRawXpSinceSyncByType.get(type.name());
		if (previous != null && expectedRaw != null && expectedRaw >= MIN_EXPECTED_XP_TO_TRUST && storedXp > previous) {
			double implied = (storedXp - previous) / (double) expectedRaw;
			double snapped = Math.round(implied / DAEMON_STEP) * DAEMON_STEP;
			double clamped = Math.max(1.0, Math.min(1.0 + DAEMON_MAX_LEVEL * DAEMON_STEP, snapped));
			if (clamped != daemonMultiplier) {
				ScdLog.info("Daemon Shard estimate updated: " + type.displayName() + " real gain " + (storedXp - previous)
						+ " vs expected " + expectedRaw + " (raw ratio " + implied + ") -> multiplier " + daemonMultiplier
						+ " -> " + clamped + " (~level " + Math.round((clamped - 1.0) / DAEMON_STEP) + ")");
			}
			daemonMultiplier = clamped;
		}
		lastRealSyncXpByType.put(type.name(), storedXp);
		expectedRawXpSinceSyncByType.put(type.name(), 0L);
		storedXpByType.put(type.name(), storedXp);
		save();
	}

	/** Current best-effort Daemon Shard multiplier (1.0-1.10) applied to future estimates - see the class doc. */
	public double daemonMultiplier() {
		return daemonMultiplier;
	}

	/** Current best-effort Daemon Shard level (0-10), derived from daemonMultiplier(). */
	public int daemonLevelEstimate() {
		return (int) Math.round((daemonMultiplier - 1.0) / DAEMON_STEP);
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
	 * shows instead of quietly ignoring the boost. Also applies the current best-effort Daemon Shard
	 * multiplier (see the class doc) and accumulates the un-boosted amount for the next reconciliation.
	 * See XP_BY_TIER/RNG_METER_MIN_TIER for where the base numbers come from and why a small
	 * inaccuracy here is low-risk - recordStoredXp always overwrites it with the authoritative value
	 * whenever a real sync arrives.
	 */
	public void recordKillTowardMeterEstimate(ScdSlayerType type, String tier, long xpGained) {
		if (tier == null) return;
		int have = TIER_ORDER.indexOf(tier);
		int min = TIER_ORDER.indexOf(RNG_METER_MIN_TIER);
		if (have < 0 || have < min) return;

		expectedRawXpSinceSyncByType.merge(type.name(), xpGained, Long::sum);
		long boosted = Math.round(xpGained * daemonMultiplier);
		long current = storedXpByType.getOrDefault(type.name(), 0L);
		storedXpByType.put(type.name(), current + boosted);
		save();
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
					selectedDropByType, requiredTotalByTypeAndDrop, lastRealSyncXpByType, expectedRawXpSinceSyncByType, daemonMultiplier)));
		} catch (IOException e) {
			ScdLog.error("Failed to save RNG meter data to " + PATH, e);
		}
	}
}
