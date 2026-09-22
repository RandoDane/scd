package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Single settings screen for every category small enough to expand in place,
 * NEU/SkyHanni-style - one big list of collapsible sections rather than a
 * stack of separate popup screens to click through. Slayer stays a
 * drill-down (its own screen) since its per-Slayer-type content is too
 * large to nest inline without the whole panel getting unwieldy.
 */
public class ScdConfigScreen extends Screen {
	private static final int PANEL_WIDTH = 320;
	private static final int PADDING = 16;
	private static final int ROW_HEIGHT = 24;
	private static final int TOGGLE_ROW_HEIGHT = 18;
	// ScdCategoryCard's own title+description text needs more vertical room than a single-line
	// ROW_HEIGHT row does - at 24 the description's own text bottom landed exactly on the card's own
	// bottom edge (zero padding), reading as "touching the bottom of the box".
	private static final int CATEGORY_CARD_HEIGHT = 32;

	// The "Themes" box: a small, separate floating panel pinned to the screen's bottom-right corner
	// rather than a section inside the main panel - a global theme isn't really a "setting" you drill
	// into, it's a one-click action you reach for from anywhere, so it gets its own always-visible spot.
	private static final int THEME_BOX_MARGIN = 16;
	private static final int THEME_BUTTONS_PER_ROW = 3;
	private static final int THEME_BUTTON_WIDTH = 78;
	private static final int THEME_BUTTON_HEIGHT = 16;
	private static final int THEME_BUTTON_GAP = 4;

	private record LabelRow(String text, int x, int y) {
	}

	private final ScdConfig config;
	private final ScdClient client;
	private boolean bazaarExpanded = false;

	private EditBox serverUrlBox;
	private int panelX, panelY, panelWidth, panelHeight;
	private int serverUrlLabelY = -1;
	private final List<LabelRow> labelRows = new ArrayList<>();
	private int themeBoxX, themeBoxY, themeBoxWidth, themeBoxHeight;

	public ScdConfigScreen(ScdConfig config, ScdClient client) {
		super(Component.literal("SCD"));
		this.config = config;
		this.client = client;
	}

	@Override
	protected void init() {
		applyServerUrlFieldIfPresent();
		labelRows.clear();
		serverUrlBox = null;

		panelWidth = PANEL_WIDTH;
		panelX = this.width / 2 - panelWidth / 2;
		panelY = 20;
		int contentX = panelX + PADDING;
		int fieldWidth = panelWidth - PADDING * 2;
		serverUrlLabelY = -1;

		int y = panelY + 32;

		// --- Bazaar ---
		addRenderableWidget(new ScdExpandableCard(contentX, y, fieldWidth, ROW_HEIGHT,
				Component.literal("Bazaar"), null, ScdTheme.ACCENT_BAZAAR, bazaarExpanded, () -> {
					applyServerUrlFieldIfPresent();
					bazaarExpanded = !bazaarExpanded;
					rebuildWidgets();
				}));
		y += ROW_HEIGHT + 4;
		if (bazaarExpanded) {
			int indentX = contentX + 10;
			int indentWidth = fieldWidth - 10;

			serverUrlLabelY = y;
			y += 10;
			serverUrlBox = new EditBox(this.font, indentX, y, indentWidth, 14, Component.literal("Server URL"));
			serverUrlBox.setValue(config.bazaar.serverUrl);
			serverUrlBox.setBordered(false);
			serverUrlBox.setTextColor(ScdTheme.TEXT_PRIMARY);
			addRenderableWidget(serverUrlBox);
			y += 22;

			y = addToggle(indentX, indentWidth, y, "Tooltip", ScdTheme.ACCENT_BAZAAR,
					config.bazaar.tooltipEnabled, v -> config.bazaar.tooltipEnabled = v);
			y = addToggle(indentX, indentWidth, y, "Graph", ScdTheme.ACCENT_BAZAAR,
					config.bazaar.graphEnabled, v -> config.bazaar.graphEnabled = v);
			y += 6;

			addRenderableWidget(new ScdButton(indentX, y, indentWidth, 16, Component.literal("Move price graph box..."), ScdTheme.ACCENT_BAZAAR, () -> {
				applyServerUrlFieldIfPresent();
				Minecraft.getInstance().setScreen(new ScdHudEditScreen(this, config, config.bazaar.graphPosition, ScdTheme.ACCENT_BAZAAR,
						(g, f) -> client.graphHud().renderContent(g, f, true)));
			}));
			y += 22 + 4;
		}

		// --- Slayer (drill-down, too large to nest inline) ---
		addRenderableWidget(new ScdCategoryCard(contentX, y, fieldWidth, CATEGORY_CARD_HEIGHT,
				Component.literal("Slayer"), "Boss tracker, per-type ability warnings, drops", ScdTheme.ACCENT_SLAYER, () -> {
					applyServerUrlFieldIfPresent();
					config.save();
					Minecraft.getInstance().setScreen(new ScdSlayerConfigScreen(this, config, client));
				}));
		y += CATEGORY_CARD_HEIGHT + 4;

		// --- Accessories (live viewer for now - a Hypixel-API-backed lookup, not a local setting,
		// so it opens straight into the viewer rather than a settings drill-down; see
		// FEATURE_ROADMAP.md §13 for what's planned on top of this) ---
		addRenderableWidget(new ScdCategoryCard(contentX, y, fieldWidth, CATEGORY_CARD_HEIGHT,
				Component.literal("Accessories"), "Your accessory bag + estimated Magical Power", ScdTheme.ACCENT_ACCESSORIES, () -> {
					applyServerUrlFieldIfPresent();
					config.save();
					Minecraft.getInstance().setScreen(new ScdAccessoryScreen(this, config, client));
				}));
		y += CATEGORY_CARD_HEIGHT + 4;

		// --- Dungeons (score HUD options, room mapping, and the Dungeon Carries entry point -
		// consolidated here 2026-09-23, was a standalone "Dungeon Carries" card straight to the
		// queue; moved to a real drill-down since there's now more than one thing to configure) ---
		addRenderableWidget(new ScdCategoryCard(contentX, y, fieldWidth, CATEGORY_CARD_HEIGHT,
				Component.literal("Dungeons"), "Score HUD, room mapping, dungeon carries", ScdTheme.ACCENT_DUNGEON, () -> {
					applyServerUrlFieldIfPresent();
					config.save();
					Minecraft.getInstance().setScreen(new ScdDungeonConfigScreen(this, config, client));
				}));
		y += CATEGORY_CARD_HEIGHT + 4;

		y += 6;
		addRenderableWidget(new ScdButton(contentX, y, fieldWidth, 16, Component.literal("Close"), ScdTheme.ACCENT_BAZAAR, () -> {
			applyServerUrlFieldIfPresent();
			config.save();
			client.onConfigChanged();
			Minecraft.getInstance().setScreen(null);
		}));
		y += 16;

		panelHeight = y + PADDING - panelY;

		initThemeBox();
	}

	/**
	 * A one-click global theme switcher, separate from the main panel (see the field-group comment
	 * above). Applying a theme overwrites every HUD's color overrides at once (right now that's just
	 * Slayer's, but any future HUD with its own ScdColorSlot enum using the same id convention picks
	 * these presets up automatically the moment it gets a hudColors map - see ScdHudTheme's doc) AND
	 * re-skins every /scd menu, including this one, via ScdTheme.applyTheme - see its own doc comment.
	 */
	private void initThemeBox() {
		int rows = (int) Math.ceil(ScdHudTheme.PRESETS.size() / (double) THEME_BUTTONS_PER_ROW);
		int contentWidth = THEME_BUTTONS_PER_ROW * THEME_BUTTON_WIDTH + (THEME_BUTTONS_PER_ROW - 1) * THEME_BUTTON_GAP;
		themeBoxWidth = contentWidth + PADDING * 2;
		themeBoxHeight = 32 + rows * THEME_BUTTON_HEIGHT + (rows - 1) * THEME_BUTTON_GAP + PADDING;
		themeBoxX = this.width - themeBoxWidth - THEME_BOX_MARGIN;
		themeBoxY = this.height - themeBoxHeight - THEME_BOX_MARGIN;

		int startX = themeBoxX + PADDING;
		int y = themeBoxY + 28;
		int col = 0;
		for (ScdHudTheme theme : ScdHudTheme.PRESETS) {
			int x = startX + col * (THEME_BUTTON_WIDTH + THEME_BUTTON_GAP);
			addRenderableWidget(new ScdButton(x, y, THEME_BUTTON_WIDTH, THEME_BUTTON_HEIGHT, Component.literal(theme.name()),
					ScdTheme.ACCENT_BAZAAR, () -> applyGlobalTheme(theme)));
			col++;
			if (col >= THEME_BUTTONS_PER_ROW) {
				col = 0;
				y += THEME_BUTTON_HEIGHT + THEME_BUTTON_GAP;
			}
		}
	}

	private void applyGlobalTheme(ScdHudTheme theme) {
		config.slayer.hudColors.clear();
		config.slayer.hudColors.putAll(theme.colors());
		config.menuTheme = theme.name();
		config.save();

		ScdTheme.applyTheme(theme);
		// Widgets already placed on THIS screen (including the theme buttons themselves) baked in
		// the old ScdTheme.ACCENT_* at construction time, so they need an explicit rebuild to pick up
		// the new one immediately - any other /scd screen opened after this point picks it up on its
		// own, just by reading the now-updated ScdTheme fields at init/render time like it always does.
		rebuildWidgets();
	}

	private int addToggle(int x, int width, int y, String label, int accentColor, boolean initial, java.util.function.Consumer<Boolean> onChange) {
		addRenderableWidget(new ScdToggle(x + width - 26, y, Component.literal(label), accentColor, initial, onChange::accept));
		labelRows.add(new LabelRow(label, x, y + 3));
		return y + TOGGLE_ROW_HEIGHT;
	}

	/** Commits whatever's currently typed before the field disappears (section collapse, navigation, or close) - otherwise an edit made just before any of those is silently lost. */
	private void applyServerUrlFieldIfPresent() {
		if (serverUrlBox == null) return;
		String url = serverUrlBox.getValue().trim();
		if (!url.isEmpty()) config.bazaar.serverUrl = url;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		ScdTheme.panel(g, panelX, panelY, panelWidth, panelHeight);
		ScdTheme.panel(g, themeBoxX, themeBoxY, themeBoxWidth, themeBoxHeight);
		super.extractRenderState(g, mouseX, mouseY, partialTick);

		ScdTheme.label(g, this.font, "SCD", panelX + PADDING, panelY + 10, ScdTheme.TEXT_PRIMARY);
		ScdTheme.divider(g, panelX + PADDING, panelY + 22, panelWidth - PADDING * 2);

		ScdTheme.label(g, this.font, "Themes", themeBoxX + PADDING, themeBoxY + 10, ScdTheme.TEXT_PRIMARY);
		ScdTheme.divider(g, themeBoxX + PADDING, themeBoxY + 22, themeBoxWidth - PADDING * 2);

		if (serverUrlLabelY >= 0) {
			ScdTheme.sectionLabel(g, this.font, "Server URL", panelX + PADDING + 10, serverUrlLabelY);
		}
		for (LabelRow row : labelRows) {
			ScdTheme.label(g, this.font, row.text(), row.x(), row.y(), ScdTheme.TEXT_SECONDARY);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
