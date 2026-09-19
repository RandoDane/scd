package com.scd.client;

/**
 * One carry-for-coins arrangement: a player being carried through a specific
 * Slayer type/tier for an agreed price, with live kill progress toward
 * however many kills that price pays for. Mutable (unlike ScdSlayerDrops'
 * immutable records) since killsCompleted/status change in place as kills
 * land - persisted as plain fields for Gson, same as ScdConfig.
 */
public class ScdCarryEntry {
	public long id;
	public String playerName;
	public String type; // ScdSlayerType.name()
	public String tier;
	public long pricePerKill;
	public long totalAmount;
	public int killsOwed;
	public long leftoverCoins;
	public int killsCompleted;
	public long totalKillTimeMs;
	public String status; // "ACTIVE" or "COMPLETED"
	public long createdAt;
	public long completedAt;

	public static ScdCarryEntry create(long id, String playerName, ScdSlayerType type, String tier, long pricePerKill, long totalAmount) {
		ScdCarryEntry entry = new ScdCarryEntry();
		entry.id = id;
		entry.playerName = playerName;
		entry.type = type.name();
		entry.tier = tier;
		entry.pricePerKill = pricePerKill;
		entry.totalAmount = totalAmount;
		entry.killsOwed = pricePerKill > 0 ? (int) (totalAmount / pricePerKill) : 0;
		entry.leftoverCoins = pricePerKill > 0 ? totalAmount % pricePerKill : totalAmount;
		entry.status = "ACTIVE";
		entry.createdAt = System.currentTimeMillis();
		return entry;
	}

	public ScdSlayerType typeEnum() {
		return ScdSlayerType.valueOf(type);
	}

	public boolean isActive() {
		return "ACTIVE".equals(status);
	}

	public double averageKillTimeMs() {
		return killsCompleted > 0 ? (double) totalKillTimeMs / killsCompleted : 0;
	}
}
