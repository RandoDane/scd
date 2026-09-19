package com.scd.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * One-time carry-over for config/data files left behind by the mod's old
 * "ccbz" name (this project was renamed to SCD) - copies the old file to its
 * new name the first time the new name doesn't exist yet, so nobody loses
 * their Slayer records, RNG meter progress, or settings just because the mod
 * was renamed. A no-op forever after the first successful copy.
 */
final class ScdDataMigration {
	private ScdDataMigration() {
	}

	static void migrateIfNeeded(Path oldPath, Path newPath) {
		try {
			if (!Files.exists(newPath) && Files.exists(oldPath)) {
				Files.copy(oldPath, newPath, StandardCopyOption.COPY_ATTRIBUTES);
				ScdLog.info("Migrated " + oldPath.getFileName() + " -> " + newPath.getFileName() + " (SCD was renamed from CCBz)");
			}
		} catch (IOException e) {
			ScdLog.error("Failed to migrate " + oldPath + " to " + newPath, e);
		}
	}
}
