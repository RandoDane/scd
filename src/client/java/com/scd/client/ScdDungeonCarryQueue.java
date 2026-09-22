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
 * Tracks dungeon carries-for-coins: who's being carried through which floor, the agreed price,
 * and live run progress. Same whole-file load/save persistence pattern as ScdCarryQueue, kept in
 * its own file (`scd_dungeon_carries.json`) rather than sharing one with Slayer carries - the two
 * entry types aren't interchangeable (see ScdDungeonCarryEntry's doc comment).
 */
public class ScdDungeonCarryQueue {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("scd_dungeon_carries.json");

	private final List<ScdDungeonCarryEntry> entries;
	private final AtomicLong nextId;

	private ScdDungeonCarryQueue(List<ScdDungeonCarryEntry> entries) {
		this.entries = entries;
		long maxId = 0;
		for (ScdDungeonCarryEntry e : entries) maxId = Math.max(maxId, e.id);
		this.nextId = new AtomicLong(maxId + 1);
	}

	public static ScdDungeonCarryQueue load() {
		try {
			if (Files.exists(PATH)) {
				var type = new TypeToken<List<ScdDungeonCarryEntry>>() {
				}.getType();
				List<ScdDungeonCarryEntry> loaded = GSON.fromJson(Files.readString(PATH), type);
				if (loaded != null) return new ScdDungeonCarryQueue(new ArrayList<>(loaded));
			}
		} catch (IOException | RuntimeException e) {
			ScdLog.error("Failed to load dungeon carry queue from " + PATH + ", starting fresh", e);
		}
		return new ScdDungeonCarryQueue(new ArrayList<>());
	}

	public ScdDungeonCarryEntry add(String playerName, String floor, long pricePerRun, long totalAmount) {
		ScdDungeonCarryEntry entry = ScdDungeonCarryEntry.create(nextId.getAndIncrement(), playerName, floor, pricePerRun, totalAmount);
		entries.add(entry);
		save();
		return entry;
	}

	/** Every active entry for a just-completed floor - one run credits all of them at once (a dungeon carry is inherently a shared-party event, not per-customer like Slayer). */
	public List<ScdDungeonCarryEntry> activeMatching(String floorKey) {
		List<ScdDungeonCarryEntry> result = new ArrayList<>();
		for (ScdDungeonCarryEntry e : entries) {
			if (e.isActive() && e.floor.equals(floorKey)) result.add(e);
		}
		return result;
	}

	/** Credits one run toward an entry's progress - same "reaching the target doesn't auto-close" behavior as ScdCarryQueue.creditKill, for the same reason (closing out is a deliberate action). Returns true the instant runsCompleted first reaches runsOwed. */
	public boolean creditRun(ScdDungeonCarryEntry entry, long runTimeMs) {
		entry.runsCompleted++;
		entry.totalRunTimeMs += runTimeMs;
		boolean justReachedTarget = entry.runsCompleted == entry.runsOwed;
		save();
		return justReachedTarget;
	}

	public ScdDungeonCarryEntry findByIdOrNull(long id) {
		for (ScdDungeonCarryEntry e : entries) {
			if (e.id == id) return e;
		}
		return null;
	}

	/** Adds more runs to an existing entry at its own already-agreed price - same semantics as ScdCarryQueue.extend. */
	public boolean extend(long id, long additionalRunCount) {
		for (ScdDungeonCarryEntry e : entries) {
			if (e.id != id) continue;
			e.runsOwed += additionalRunCount;
			e.totalAmount += e.pricePerRun * additionalRunCount;
			if ("COMPLETED".equals(e.status) && e.runsCompleted < e.runsOwed) {
				e.status = "ACTIVE";
				e.completedAt = 0;
			}
			save();
			return true;
		}
		return false;
	}

	/** Every entry, active ones first (oldest first), then completed ones (most recently finished first). */
	public List<ScdDungeonCarryEntry> all() {
		List<ScdDungeonCarryEntry> sorted = new ArrayList<>(entries);
		sorted.sort((a, b) -> {
			if (a.isActive() != b.isActive()) return a.isActive() ? -1 : 1;
			return a.isActive() ? Long.compare(a.createdAt, b.createdAt) : Long.compare(b.completedAt, a.completedAt);
		});
		return sorted;
	}

	public List<ScdDungeonCarryEntry> active() {
		List<ScdDungeonCarryEntry> result = new ArrayList<>();
		for (ScdDungeonCarryEntry e : entries) if (e.isActive()) result.add(e);
		return result;
	}

	/** Whichever entry was created most recently - lets the "New Carry" form default to whatever floor you're currently carrying instead of always F1. */
	public ScdDungeonCarryEntry mostRecentOrNull() {
		ScdDungeonCarryEntry latest = null;
		for (ScdDungeonCarryEntry e : entries) {
			if (latest == null || e.createdAt > latest.createdAt) latest = e;
		}
		return latest;
	}

	public int activeCount() {
		int count = 0;
		for (ScdDungeonCarryEntry e : entries) if (e.isActive()) count++;
		return count;
	}

	public boolean markComplete(long id) {
		for (ScdDungeonCarryEntry e : entries) {
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
			ScdLog.error("Failed to save dungeon carry queue to " + PATH, e);
		}
	}
}
