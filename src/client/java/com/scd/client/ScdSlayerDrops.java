package com.scd.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists a running count of every item picked up while a given Slayer
 * type's quest was active - not a curated "boss drop" table (there's no
 * reliable way to tell a real boss drop apart from incidental loot picked up
 * during the grind), just an honest tally of what came in during that quest.
 * Good enough to answer "what have I actually gotten doing this slayer"
 * without pretending to a precision that can't be verified.
 */
public class ScdSlayerDrops {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("scd_slayer_drops.json");

	public record ItemCount(String displayName, int count) {
	}

	// slayer type name -> item id -> running count
	private final Map<String, Map<String, ItemCount>> byType;

	private ScdSlayerDrops(Map<String, Map<String, ItemCount>> byType) {
		this.byType = byType;
	}

	public static ScdSlayerDrops load() {
		ScdDataMigration.migrateIfNeeded(FabricLoader.getInstance().getConfigDir().resolve("ccbz_slayer_drops.json"), PATH);
		try {
			if (Files.exists(PATH)) {
				var type = new TypeToken<Map<String, Map<String, ItemCount>>>() {
				}.getType();
				Map<String, Map<String, ItemCount>> loaded = GSON.fromJson(Files.readString(PATH), type);
				if (loaded != null) return new ScdSlayerDrops(new HashMap<>(loaded));
			}
		} catch (IOException | RuntimeException e) {
			ScdLog.error("Failed to load slayer drop data from " + PATH + ", starting fresh", e);
		}
		return new ScdSlayerDrops(new HashMap<>());
	}

	public void record(ScdSlayerType type, String itemId, String displayName, int amount) {
		var items = byType.computeIfAbsent(type.name(), k -> new LinkedHashMap<>());
		ItemCount existing = items.get(itemId);
		items.put(itemId, new ItemCount(displayName, (existing != null ? existing.count() : 0) + amount));
		save();
	}

	/** Top drops for a type, highest count first. */
	public List<ItemCount> topForType(ScdSlayerType type, int limit) {
		List<ItemCount> all = new ArrayList<>(byType.getOrDefault(type.name(), Map.of()).values());
		all.sort((a, b) -> Integer.compare(b.count(), a.count()));
		return all.subList(0, Math.min(limit, all.size()));
	}

	public record Entry(String itemId, String displayName, int count) {
	}

	/** Every drop recorded for a type, highest count first, with the item id needed to remove one. */
	public List<Entry> entriesForType(ScdSlayerType type) {
		List<Entry> all = new ArrayList<>();
		byType.getOrDefault(type.name(), Map.of()).forEach((id, item) -> all.add(new Entry(id, item.displayName(), item.count())));
		all.sort((a, b) -> Integer.compare(b.count(), a.count()));
		return all;
	}

	/** Wipes every recorded drop for a single Slayer type. */
	public void clear(ScdSlayerType type) {
		if (byType.remove(type.name()) != null) save();
	}

	/** Wipes recorded drops for every Slayer type. */
	public void clearAll() {
		byType.clear();
		save();
	}

	/** Removes one item's running count from a type's drop list. */
	public boolean remove(ScdSlayerType type, String itemId) {
		var items = byType.get(type.name());
		if (items == null || items.remove(itemId) == null) return false;
		save();
		return true;
	}

	private void save() {
		try {
			Files.writeString(PATH, GSON.toJson(byType));
		} catch (IOException e) {
			ScdLog.error("Failed to save slayer drop data to " + PATH, e);
		}
	}
}
