package com.scd.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A named bundle of color-slot overrides, applied in one click from ScdConfigScreen's "Themes" box
 * instead of setting each hex value by hand. Keyed by the same slot id strings a HUD's ScdColorSlot
 * enum uses (background/bossTitle/bossText/statsLabel/statsValue for now, the ids ScdSlayerColorSlot
 * defines) - a future HUD that reuses those same conceptual roles under the same ids picks up these
 * presets for free, no per-HUD theme data needed, just {@code colorOverrides.putAll(theme.colors())}.
 *
 * Since 2026-09-22 also the single source for every /scd menu's own palette (see
 * ScdTheme.applyTheme) - bossTitle becomes the one shared accent color everywhere (replacing
 * Bazaar/Slayer/Accessories' previously-distinct blue/red/gold), bossText/statsLabel/statsValue
 * become the shared secondary/muted/primary text colors, and background tints every panel/card
 * gradient. The selected theme's name is persisted at ScdConfig.menuTheme and reapplied at startup,
 * so menus look themed from the moment the game opens, not just after visiting /scd.
 *
 * The five presets below follow established game-HUD color conventions rather than arbitrary picks:
 * - Classic: warm red for combat/danger feedback against neutral cool grays - the mod's original look.
 * - Cyberpunk: a cyan/magenta split-complementary neon pairing on a cool-tinted background, the
 *   sci-fi/hacker-UI convention (Cyberpunk 2077, Deus Ex) where a couple of saturated hues against
 *   near-black reads as "tech" without competing with the gameplay behind it.
 * - Emerald: green/gold loot-rarity language RPG players already read intuitively (gold = premium/
 *   reward, green = safe/positive), so the title reads as "this matters" before it's even parsed.
 * - Arctic: an analogous cool-blue family (low hue variance) for a calmer, visually quieter HUD -
 *   fewer competing hues means the eye settles on brightness/position instead of color-hunting.
 * - Monochrome: pure grayscale, contrast from luminance alone rather than hue - the standard
 *   colorblind-safe pattern, since it never depends on distinguishing a specific color pair.
 * Every preset keeps its brightest text (title/value) near-white against the panel's own near-black
 * background, comfortably clearing WCAG's 4.5:1 body-text contrast minimum.
 */
public record ScdHudTheme(String name, Map<String, Integer> colors) {
	public static final List<ScdHudTheme> PRESETS = List.of(
			theme("Classic", 0xFFFFFFFF, 0xFFEF5B5B, 0xFFA7ADBB, 0xFF6B7280, 0xFFF4F6FA),
			theme("Cyberpunk", 0xFFB9C6FF, 0xFF00E5FF, 0xFFB8C6E0, 0xFFB06BC9, 0xFFF2F2FF),
			theme("Emerald", 0xFFC9F5D9, 0xFFFFD54A, 0xFFA9D8B8, 0xFF7FA98D, 0xFFF5F0DC),
			theme("Arctic", 0xFFCFE8FF, 0xFF6FD3FF, 0xFFBFD9EC, 0xFF7996AD, 0xFFEAF6FF),
			theme("Monochrome", 0xFFFFFFFF, 0xFFFFFFFF, 0xFFCBCBCB, 0xFF8A8A8A, 0xFFFFFFFF)
	);

	/** Looks up a preset by its exact name (as saved in ScdConfig.menuTheme) - falls back to the first preset (Classic) for an unrecognized/missing name rather than crashing, e.g. a config saved by a version with a since-renamed/removed theme. */
	public static ScdHudTheme byName(String name) {
		return PRESETS.stream().filter(t -> t.name().equals(name)).findFirst().orElse(PRESETS.get(0));
	}

	private static ScdHudTheme theme(String name, int background, int bossTitle, int bossText, int statsLabel, int statsValue) {
		Map<String, Integer> colors = new LinkedHashMap<>();
		colors.put(ScdSlayerColorSlot.BACKGROUND.id(), background);
		colors.put(ScdSlayerColorSlot.BOSS_TITLE.id(), bossTitle);
		colors.put(ScdSlayerColorSlot.BOSS_TEXT.id(), bossText);
		colors.put(ScdSlayerColorSlot.STATS_LABEL.id(), statsLabel);
		colors.put(ScdSlayerColorSlot.STATS_VALUE.id(), statsValue);
		return new ScdHudTheme(name, colors);
	}
}
