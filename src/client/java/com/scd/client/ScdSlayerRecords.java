package com.scd.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Persists the player's fastest kill time per Slayer type+tier across sessions. */
public class ScdSlayerRecords {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("scd_slayer_records.json");

	private final Map<String, Long> bestMs;

	private ScdSlayerRecords(Map<String, Long> bestMs) {
		this.bestMs = bestMs;
	}

	public static ScdSlayerRecords load() {
		ScdDataMigration.migrateIfNeeded(FabricLoader.getInstance().getConfigDir().resolve("ccbz_slayer_records.json"), PATH);
		try {
			if (Files.exists(PATH)) {
				Map<String, Long> loaded = GSON.fromJson(Files.readString(PATH), new TypeToken<Map<String, Long>>() {
				}.getType());
				if (loaded != null) return new ScdSlayerRecords(new HashMap<>(loaded));
			}
		} catch (IOException | RuntimeException e) {
			ScdLog.error("Failed to load slayer records from " + PATH + ", starting fresh", e);
		}
		return new ScdSlayerRecords(new HashMap<>());
	}

	private static String key(ScdSlayerType type, String tier) {
		return type.name() + "_" + (tier != null ? tier : "?");
	}

	public Long best(ScdSlayerType type, String tier) {
		return bestMs.get(key(type, tier));
	}

	/** Records a completed fight, returning true if it's a new personal best (including the very first recorded kill of that type+tier). */
	public boolean recordKill(ScdSlayerType type, String tier, long elapsedMs) {
		String k = key(type, tier);
		Long current = bestMs.get(k);
		if (current == null || elapsedMs < current) {
			bestMs.put(k, elapsedMs);
			save();
			return true;
		}
		return false;
	}

	private void save() {
		try {
			Files.writeString(PATH, GSON.toJson(bestMs));
		} catch (IOException e) {
			ScdLog.error("Failed to save slayer records to " + PATH, e);
		}
	}
}
