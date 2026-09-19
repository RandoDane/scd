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

	private record LabelRow(String text, int x, int y) {
	}

	private final ScdConfig config;
	private final ScdClient client;
	private boolean bazaarExpanded = false;

	private EditBox serverUrlBox;
	private int panelX, panelY, panelWidth, panelHeight;
	private int serverUrlLabelY = -1;
	private final List<LabelRow> labelRows = new ArrayList<>();

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
		addRenderableWidget(new ScdCategoryCard(contentX, y, fieldWidth, ROW_HEIGHT,
				Component.literal("Slayer"), "Boss tracker, per-type ability warnings, drops", ScdTheme.ACCENT_SLAYER, () -> {
					applyServerUrlFieldIfPresent();
					config.save();
					Minecraft.getInstance().setScreen(new ScdSlayerConfigScreen(this, config, client));
				}));
		y += ROW_HEIGHT + 4;

		y += 6;
		addRenderableWidget(new ScdButton(contentX, y, fieldWidth, 16, Component.literal("Close"), ScdTheme.ACCENT_BAZAAR, () -> {
			applyServerUrlFieldIfPresent();
			config.save();
			client.onConfigChanged();
			Minecraft.getInstance().setScreen(null);
		}));
		y += 16;

		panelHeight = y + PADDING - panelY;
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
		super.extractRenderState(g, mouseX, mouseY, partialTick);

		ScdTheme.label(g, this.font, "SCD", panelX + PADDING, panelY + 10, ScdTheme.TEXT_PRIMARY);
		ScdTheme.divider(g, panelX + PADDING, panelY + 22, panelWidth - PADDING * 2);

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
