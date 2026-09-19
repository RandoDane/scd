package com.scd.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ScdConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("scd.json");

	public static class Pos {
		public int x;
		public int y;
		// Uniform size multiplier for the whole box (text, panel, everything scales together) - set by
		// dragging the corner handle in ScdHudEditScreen, 1.0 = the box's normal/native size.
		public float scale = 1.0f;

		public Pos(int x, int y) {
			this.x = x;
			this.y = y;
		}
	}

	public Bazaar bazaar = new Bazaar();
	public Slayer slayer = new Slayer();

	// Gates the diagnostic/debug commands (see ScdClient.registerCommands) behind /scd dev <code>, so
	// the command list doesn't look bloated with internal-only tools when handed to someone who isn't
	// actively troubleshooting an issue.
	public boolean devUnlocked = false;

	public static class Bazaar {
		public String serverUrl = "http://localhost:3000";
		public boolean tooltipEnabled = true;

		public boolean graphEnabled = true;
		public String graphRange = "7d";
		public Pos graphPosition = new Pos(8, 8);
	}

	public static class Slayer {
		public boolean bossTrackerEnabled = true;
		public Pos bossTrackerPosition = new Pos(8, 120);

		public boolean minibossAlertEnabled = true;

		// World-space glow + line to whichever boss is currently tracked - see ScdGlowRegistry/
		// ScdGizmoUtil, gated on this instead of always-on so it can be turned off on a crowded
		// shared island where every nearby glow starts adding visual noise.
		public boolean bossHighlightEnabled = true;

		public boolean statsHudEnabled = true;
		public Pos statsHudPosition = new Pos(8, 220);

		// Per-type ability call-outs shown in the boss tracker box while that type's boss is up -
		// split out so any one cue can be turned off individually instead of all-or-nothing.
		public Zombie zombie = new Zombie();
		public Vampire vampire = new Vampire();
		public Spider spider = new Spider();
		public Blaze blaze = new Blaze();
		public Enderman enderman = new Enderman();
		public Wolf wolf = new Wolf();
	}

	public static class Zombie {
		public boolean enrageEnabled = true;
	}

	public static class Vampire {
		public boolean twinclawEnabled = true;
		public boolean maniaEnabled = true;
	}

	public static class Spider {
		public boolean eggSacEnabled = true;
		public boolean conjoinedBroodWarningEnabled = true;
	}

	public static class Blaze {
		public boolean firePillarEnabled = true;
		public boolean demonsplitEnabled = true;
	}

	public static class Enderman {
		public boolean beamPhaseEnabled = true;
		public boolean hitshieldEnabled = true;

		// An Ender Slayer-specific tool, not a general ammo readout - belongs with the rest of the
		// Enderman-specific options.
		public boolean explosiveArrowCounterEnabled = true;
		public Pos explosiveArrowCounterPosition = new Pos(8, 340);
	}

	public static class Wolf {
		public boolean callThePupsEnabled = true;
	}

	// Fields that used to live at the top level, before settings were split into
	// per-feature categories (bazaar, slayer, ...). Migrated into "bazaar" on load
	// so upgrading doesn't silently reset an already-configured server URL.
	private static final String[] LEGACY_BAZAAR_KEYS = { "serverUrl", "tooltipEnabled", "graphEnabled", "graphRange", "graphPosition" };

	public static ScdConfig load() {
		ScdDataMigration.migrateIfNeeded(FabricLoader.getInstance().getConfigDir().resolve("ccbz.json"), PATH);
		try {
			if (Files.exists(PATH)) {
				JsonObject root = GSON.fromJson(Files.readString(PATH), JsonObject.class);
				if (root != null) {
					migrateLegacyBazaarFields(root);
					ScdConfig loaded = GSON.fromJson(root, ScdConfig.class);
					if (loaded != null) {
						loaded.logEffectiveSettings();
						return loaded;
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			ScdLog.error("Failed to load config from " + PATH + ", using defaults", e);
		}
		ScdConfig fresh = new ScdConfig();
		fresh.save();
		fresh.logEffectiveSettings();
		return fresh;
	}

	/**
	 * Printed at INFO on every launch specifically so "it doesn't work" reports
	 * can be diagnosed from a pasted log alone - e.g. a friend who never ran
	 * /scd server still pointing at the default localhost:3000, which will
	 * never work off of their own machine.
	 */
	private void logEffectiveSettings() {
		ScdLog.info("Config loaded from " + PATH + " - bazaar.serverUrl=" + bazaar.serverUrl
				+ ", bazaar.tooltipEnabled=" + bazaar.tooltipEnabled + ", bazaar.graphEnabled=" + bazaar.graphEnabled
				+ ", slayer.bossTrackerEnabled=" + slayer.bossTrackerEnabled);
	}

	private static void migrateLegacyBazaarFields(JsonObject root) {
		JsonObject bazaar = root.has("bazaar") && root.get("bazaar").isJsonObject() ? root.getAsJsonObject("bazaar") : new JsonObject();
		boolean migrated = false;
		for (String key : LEGACY_BAZAAR_KEYS) {
			if (root.has(key)) {
				bazaar.add(key, root.remove(key));
				migrated = true;
			}
		}
		if (migrated) root.add("bazaar", bazaar);
	}

	public void save() {
		try {
			Files.writeString(PATH, GSON.toJson(this));
		} catch (IOException e) {
			ScdLog.error("Failed to save config to " + PATH, e);
		}
	}
}
