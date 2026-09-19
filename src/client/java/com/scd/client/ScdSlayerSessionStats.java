package com.scd.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory (not persisted across restarts) session stats: kill count,
 * average kill (fight) time, average hunt (quest-to-spawn) time, and a
 * kills/hour rate derived from the two combined, kept separately per tier
 * since a Tier I cycle and a Tier IV cycle take wildly different amounts of
 * time - mixing them into one average would be meaningless. Switching to a
 * tier with no recorded kills yet naturally shows 0 until its first kill
 * lands.
 */
public class ScdSlayerSessionStats {
	private final Map<String, List<Long>> killTimesByTier = new HashMap<>();
	private final Map<String, List<Long>> huntTimesByTier = new HashMap<>();
	private String currentTier;
	// Not tier-scoped like the rest of this class: Slayer XP counts toward that Slayer type's overall
	// level regardless of which tier earned it, so a single running total for the session is the
	// meaningful number rather than one bucket per tier.
	private long totalXpGained;
	// Everything above is only ever keyed by TIER, never by type - switching from Spider IV to
	// Enderman IV would otherwise silently mix both types' kills/times into the same "IV" bucket, and
	// totalXpGained has no tier-keying to fall back on at all. setCurrentType below is the guard: a
	// genuine type change clears everything, since it's a different grind entirely, not just a
	// different tier of the same one (which legitimately keeps separate per-tier buckets on purpose).
	private ScdSlayerType currentType;

	public void recordKill(String tier, long elapsedMs) {
		if (tier == null) return;
		currentTier = tier;
		killTimesByTier.computeIfAbsent(tier, t -> new ArrayList<>()).add(elapsedMs);
	}

	/** Records how long the hunting phase (quest accepted to boss spawned, idle time excluded) took for this tier. */
	public void recordHunt(String tier, long elapsedMs) {
		if (tier == null) return;
		currentTier = tier;
		huntTimesByTier.computeIfAbsent(tier, t -> new ArrayList<>()).add(elapsedMs);
	}

	public long averageHuntMs() {
		var times = currentTier != null ? huntTimesByTier.getOrDefault(currentTier, List.of()) : List.<Long>of();
		if (times.isEmpty()) return 0;
		long sum = 0;
		for (long t : times) sum += t;
		return sum / times.size();
	}

	/** Switches which tier's stats are shown, without needing a kill first - e.g. the moment the active quest's tier changes. */
	public void setCurrentTier(String tier) {
		if (tier != null) currentTier = tier;
	}

	/**
	 * Called every tick with whatever Slayer type is currently active. Switching to a genuinely
	 * different type (Spider -> Enderman, say) wipes every accumulated stat - kills, times, XP - since
	 * that's a different grind, not a continuation of the same one. Switching TIER within the same
	 * type does NOT reset anything; that's the existing, intentional per-tier bucketing above.
	 */
	public void setCurrentType(ScdSlayerType type) {
		if (type == null) return;
		if (currentType != null && currentType != type) {
			killTimesByTier.clear();
			huntTimesByTier.clear();
			totalXpGained = 0;
			currentTier = null;
		}
		currentType = type;
	}

	public String currentTierOrNull() {
		return currentTier;
	}

	public int killCount() {
		return currentTierTimes().size();
	}

	public long averageKillMs() {
		var times = currentTierTimes();
		if (times.isEmpty()) return 0;
		long sum = 0;
		for (long t : times) sum += t;
		return sum / times.size();
	}

	/**
	 * Extrapolated from this tier's own average full cycle (hunting for the boss to spawn, then
	 * fighting it), not real elapsed session time - a session spanning several tiers would otherwise
	 * understate whichever tier wasn't being farmed the whole time. Falls back to fight time alone
	 * if no hunt has been recorded yet for this tier (e.g. right after a game restart, before the
	 * first quest-to-spawn cycle completes), since a boss doesn't spawn out of nowhere.
	 */
	public double killsPerHour() {
		long avgFight = averageKillMs();
		long avgHunt = averageHuntMs();
		long avgCycle = avgFight + avgHunt;
		return avgCycle > 0 ? 3_600_000.0 / avgCycle : 0;
	}

	private List<Long> currentTierTimes() {
		return currentTier != null ? killTimesByTier.getOrDefault(currentTier, List.of()) : List.of();
	}

	/** Adds Slayer XP gained from a single kill - already scaled by any active mayor/minister perk boost, see ScdMayorPerks. */
	public void recordXpGained(long amount) {
		totalXpGained += amount;
	}

	public long totalXpGained() {
		return totalXpGained;
	}
}
