package com.scd.client;

/**
 * One dungeon carry-for-coins arrangement: a player being carried through a specific floor
 * (e.g. "F5", "M6" - ScdDungeonCompletion's canonical floor key) for an agreed price, with live
 * run progress toward however many runs that price pays for. Mirrors ScdCarryEntry's shape
 * (kills -> runs, type+tier -> floor) - kept as a separate class rather than generalizing the
 * Slayer one, since dungeon crediting works fundamentally differently (see
 * FEATURE_ROADMAP.md §20): a Slayer carry watches for one specific customer's own boss dying,
 * independent of what the carrier is doing; a dungeon carry is credited by the carrier's OWN
 * dungeon run completing, since carrying a dungeon means being in the same party/instance as the
 * customer - there's no per-player boss-ownership tag to watch for instead.
 */
public class ScdDungeonCarryEntry {
	public long id;
	public String playerName;
	public String floor; // ScdDungeonCompletion.CompletionReport.floorKey(), e.g. "F5"/"M6"
	public long pricePerRun;
	public long totalAmount;
	public int runsOwed;
	public long leftoverCoins;
	public int runsCompleted;
	public long totalRunTimeMs;
	public String status; // "ACTIVE" or "COMPLETED"
	public long createdAt;
	public long completedAt;

	public static ScdDungeonCarryEntry create(long id, String playerName, String floor, long pricePerRun, long totalAmount) {
		ScdDungeonCarryEntry entry = new ScdDungeonCarryEntry();
		entry.id = id;
		entry.playerName = playerName;
		entry.floor = floor;
		entry.pricePerRun = pricePerRun;
		entry.totalAmount = totalAmount;
		entry.runsOwed = pricePerRun > 0 ? (int) (totalAmount / pricePerRun) : 0;
		entry.leftoverCoins = pricePerRun > 0 ? totalAmount % pricePerRun : totalAmount;
		entry.status = "ACTIVE";
		entry.createdAt = System.currentTimeMillis();
		return entry;
	}

	public boolean isActive() {
		return "ACTIVE".equals(status);
	}

	public double averageRunTimeMs() {
		return runsCompleted > 0 ? (double) totalRunTimeMs / runsCompleted : 0;
	}
}
