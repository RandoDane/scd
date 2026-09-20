package com.scd.client;

/**
 * Customizable colors on the combined Slayer HUD (see ScdSlayerHud/ScdSlayerStatsHud) - overrides
 * are stored by id in ScdConfig.Slayer.hudColors, edited via ScdHudAppearanceScreen. Defaults match
 * whatever ScdTheme constant that region used before this customization existed, so an untouched
 * config renders pixel-identical to before.
 */
public enum ScdSlayerColorSlot implements ScdColorSlot {
	BACKGROUND("background", "HUD Background", 0xFFFFFFFF),
	BOSS_TITLE("bossTitle", "Boss Name (top section)", ScdTheme.ACCENT_SLAYER),
	BOSS_TEXT("bossText", "Boss Info Text (top section)", ScdTheme.TEXT_SECONDARY),
	STATS_LABEL("statsLabel", "Session Stats Labels", ScdTheme.TEXT_MUTED),
	STATS_VALUE("statsValue", "Session Stats Text", ScdTheme.TEXT_PRIMARY);

	private final String id;
	private final String label;
	private final int defaultColor;

	ScdSlayerColorSlot(String id, String label, int defaultColor) {
		this.id = id;
		this.label = label;
		this.defaultColor = defaultColor;
	}

	@Override
	public String id() {
		return id;
	}

	@Override
	public String label() {
		return label;
	}

	@Override
	public int defaultColor() {
		return defaultColor;
	}
}
