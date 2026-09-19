package com.scd.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks carries-for-coins: who's being carried through which Slayer
 * type/tier, the agreed price, and live kill progress toward however many
 * kills that price pays for. Persisted the same whole-file load/save pattern
 * as ScdSlayerDrops/ScdSlayerRecords.
 */
public class ScdCarryQueue {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("scd_carries.json");

	private final List<ScdCarryEntry> entries;
	private final AtomicLong nextId;

	private ScdCarryQueue(List<ScdCarryEntry> entries) {
		this.entries = entries;
		long maxId = 0;
		for (ScdCarryEntry e : entries) maxId = Math.max(maxId, e.id);
		this.nextId = new AtomicLong(maxId + 1);
	}

	public static ScdCarryQueue load() {
		try {
			if (Files.exists(PATH)) {
				var type = new TypeToken<List<ScdCarryEntry>>() {
				}.getType();
				List<ScdCarryEntry> loaded = GSON.fromJson(Files.readString(PATH), type);
				if (loaded != null) return new ScdCarryQueue(new ArrayList<>(loaded));
			}
		} catch (IOException | RuntimeException e) {
			ScdLog.error("Failed to load carry queue from " + PATH + ", starting fresh", e);
		}
		return new ScdCarryQueue(new ArrayList<>());
	}

	public ScdCarryEntry add(String playerName, ScdSlayerType type, String tier, long pricePerKill, long totalAmount) {
		ScdCarryEntry entry = ScdCarryEntry.create(nextId.getAndIncrement(), playerName, type, tier, pricePerKill, totalAmount);
		entries.add(entry);
		save();
		return entry;
	}

	/** Every active entry matching a just-completed boss's exact type+tier - one kill can credit several at once (multiple customers carried together in the same party). */
	public List<ScdCarryEntry> activeMatching(ScdSlayerType type, String tier) {
		List<ScdCarryEntry> result = new ArrayList<>();
		for (ScdCarryEntry e : entries) {
			if (e.isActive() && e.type.equals(type.name()) && e.tier.equals(tier)) result.add(e);
		}
		return result;
	}

	/** Credits one kill toward an entry's progress, returning true if this kill just finished it. */
	public boolean creditKill(ScdCarryEntry entry, long killTimeMs) {
		entry.killsCompleted++;
		entry.totalKillTimeMs += killTimeMs;
		boolean justCompleted = entry.killsCompleted >= entry.killsOwed;
		if (justCompleted) {
			entry.status = "COMPLETED";
			entry.completedAt = System.currentTimeMillis();
		}
		save();
		return justCompleted;
	}

	/**
	 * Adds more bosses to an existing entry at its own already-agreed price
	 * (a repeat customer at the same deal, not a renegotiation), returning
	 * true if it found the entry. Reactivates a COMPLETED entry if the new
	 * total isn't fully credited yet - "add more" is meant to keep tracking
	 * the same relationship, not spawn a parallel one.
	 */
	public boolean extend(long id, long additionalBossCount) {
		for (ScdCarryEntry e : entries) {
			if (e.id != id) continue;
			e.killsOwed += additionalBossCount;
			e.totalAmount += e.pricePerKill * additionalBossCount;
			if ("COMPLETED".equals(e.status) && e.killsCompleted < e.killsOwed) {
				e.status = "ACTIVE";
				e.completedAt = 0;
			}
			save();
			return true;
		}
		return false;
	}

	/** Every entry, active ones first (oldest first), then completed ones (most recently finished first). */
	public List<ScdCarryEntry> all() {
		List<ScdCarryEntry> sorted = new ArrayList<>(entries);
		sorted.sort((a, b) -> {
			if (a.isActive() != b.isActive()) return a.isActive() ? -1 : 1;
			return a.isActive() ? Long.compare(a.createdAt, b.createdAt) : Long.compare(b.completedAt, a.completedAt);
		});
		return sorted;
	}

	/** Every active entry, unsorted - for feeding ScdCarryBossWatcher each tick. */
	public List<ScdCarryEntry> active() {
		List<ScdCarryEntry> result = new ArrayList<>();
		for (ScdCarryEntry e : entries) if (e.isActive()) result.add(e);
		return result;
	}

	/** Whichever entry was created most recently (active or not) - lets the "New Carry" form default to whatever type/tier you're currently running instead of always Zombie III. */
	public ScdCarryEntry mostRecentOrNull() {
		ScdCarryEntry latest = null;
		for (ScdCarryEntry e : entries) {
			if (latest == null || e.createdAt > latest.createdAt) latest = e;
		}
		return latest;
	}

	public int activeCount() {
		int count = 0;
		for (ScdCarryEntry e : entries) if (e.isActive()) count++;
		return count;
	}

	/** Manually marks an entry complete regardless of kill progress - for a customer who stops early. */
	public boolean markComplete(long id) {
		for (ScdCarryEntry e : entries) {
			if (e.id == id && e.isActive()) {
				e.status = "COMPLETED";
				e.completedAt = System.currentTimeMillis();
				save();
				return true;
			}
		}
		return false;
	}

	public boolean remove(long id) {
		if (entries.removeIf(e -> e.id == id)) {
			save();
			return true;
		}
		return false;
	}

	private void save() {
		try {
			Files.writeString(PATH, GSON.toJson(entries));
		} catch (IOException e) {
			ScdLog.error("Failed to save carry queue to " + PATH, e);
		}
	}
}
