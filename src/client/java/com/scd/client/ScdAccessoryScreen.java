package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Live view of the logged-in player's own Accessory Bag, read via the server's Hypixel-API-backed
 * profile endpoint (see ScdApiClient.fetchAccessories / server/src/hypixelProfile.js) rather than
 * anything read locally - this is the first SCD feature whose data comes from a per-player
 * authenticated lookup instead of a public/cached one, so it can be slow (a real network round trip
 * to Hypixel, not a local cache hit) and can fail in ways the rest of the mod doesn't (missing
 * server API key, unknown username, Hypixel API errors) - all three states are shown explicitly
 * rather than the screen just sitting blank.
 *
 * Polls ScdAccessoryData's status every frame and rebuilds its own widgets on a transition rather
 * than being pushed to directly - the fetch's completion callback runs via
 * Minecraft.getInstance().execute() (see ScdClient.refreshAccessories()) with no reference back to
 * whichever screen happens to be open when it lands, so the screen has to notice for itself that
 * new data arrived instead of vice versa.
 */
public class ScdAccessoryScreen extends Screen {
	private static final int PANEL_WIDTH = 300;
	private static final int PADDING = 16;
	private static final int ROW_HEIGHT = 18;
	private static final int ROWS_PER_PAGE = 8;

	private record RowLabel(String text, int y, int color) {
	}

	private final Screen parent;
	private final ScdConfig config;
	private final ScdClient client;
	private int page = 0;
	private boolean showMissing = false;
	private ScdAccessoryData.Status lastSeenStatus;

	private int panelX, panelY, panelWidth, panelHeight;
	private int summaryLabelY = -1;
	private int statusLabelY = -1;
	private int pageLabelY = -1;
	private int missingOverlayLabelY = -1;
	private final List<RowLabel> rowLabels = new ArrayList<>();

	public ScdAccessoryScreen(Screen parent, ScdConfig config, ScdClient client) {
		super(Component.literal("SCD - Accessories"));
		this.config = config;
		this.parent = parent;
		this.client = client;
	}

	@Override
	protected void init() {
		if (client.accessoryData().status() == ScdAccessoryData.Status.IDLE) {
			client.refreshAccessories();
		}
		lastSeenStatus = client.accessoryData().status();

		rowLabels.clear();
		summaryLabelY = -1;
		statusLabelY = -1;
		pageLabelY = -1;

		panelWidth = PANEL_WIDTH;
		panelX = this.width / 2 - panelWidth / 2;
		panelY = this.height / 2 - 140;
		int contentX = panelX + PADDING;
		int fieldWidth = panelWidth - PADDING * 2;

		int y = panelY + 32;

		switch (client.accessoryData().status()) {
			case IDLE, LOADING -> {
				statusLabelY = y;
				y += ROW_HEIGHT;
			}
			case ERROR -> {
				statusLabelY = y;
				y += ROW_HEIGHT + 6;
				addRenderableWidget(new ScdButton(contentX, y, fieldWidth, 16, Component.literal("Retry"), ScdTheme.ACCENT_ACCESSORIES, client::refreshAccessories));
				y += 22;
			}
			case LOADED -> y = layoutLoaded(contentX, fieldWidth, y);
		}
		y += 6;

		// Gates a planned feature (see ScdConfig.Accessories' own doc comment) - the toggle already
		// exists and persists even though the scan/overlay it controls isn't built yet, so it's ready
		// the moment that lands instead of needing a second config-plumbing pass.
		missingOverlayLabelY = y + 3;
		addRenderableWidget(new ScdToggle(contentX + fieldWidth - 26, y, Component.literal("Missing accessories overlay"),
				ScdTheme.ACCENT_ACCESSORIES, config.accessories.missingAccessoriesOverlayEnabled,
				v -> config.accessories.missingAccessoriesOverlayEnabled = v));
		y += ROW_HEIGHT + 6;

		int buttonWidth = (fieldWidth - 8) / 2;
		addRenderableWidget(new ScdButton(contentX, y, buttonWidth, 16, Component.literal("Back"), ScdTheme.ACCENT_ACCESSORIES, () -> {
			config.save();
			Minecraft.getInstance().setScreen(parent);
		}));
		addRenderableWidget(new ScdButton(contentX + buttonWidth + 8, y, buttonWidth, 16, Component.literal("Close"), ScdTheme.ACCENT_ACCESSORIES, () -> {
			config.save();
			Minecraft.getInstance().setScreen(null);
		}));
		y += 16;

		panelHeight = y + PADDING - panelY;
	}

	private int layoutLoaded(int contentX, int fieldWidth, int y) {
		var summary = client.accessoryData().summary();
		summaryLabelY = y;
		y += ROW_HEIGHT + 4;

		// Two views over the same fetch, not two separate fetches - "missing" is a naive id diff
		// against Hypixel's own item list computed server-side (see AccessorySummary's own doc
		// comment for the known upgrade-family over-counting caveat), so it needs no extra request.
		List<String> rowTexts = showMissing
				? summary.missingAccessories().stream().map(ScdApiClient.MissingAccessory::name).toList()
				: summary.accessories().stream().map(acc -> {
					String countSuffix = acc.count() > 1 ? " x" + acc.count() : "";
					String rarityLabel = acc.rarity() != null ? acc.rarity() : "?";
					return acc.name() + countSuffix + " - " + rarityLabel + " (" + acc.magicalPower() + " MP)";
				}).toList();

		int pageCount = Math.max(1, (rowTexts.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
		page = Math.max(0, Math.min(page, pageCount - 1));

		if (rowTexts.isEmpty()) {
			statusLabelY = y;
			y += ROW_HEIGHT;
		} else {
			int from = page * ROWS_PER_PAGE;
			int to = Math.min(from + ROWS_PER_PAGE, rowTexts.size());
			for (int i = from; i < to; i++) {
				rowLabels.add(new RowLabel(rowTexts.get(i), y + 3, ScdTheme.TEXT_SECONDARY));
				y += ROW_HEIGHT;
			}
		}
		y += 6;

		if (pageCount > 1) {
			pageLabelY = y;
			y += 10;

			int navWidth = (fieldWidth - 8) / 2;
			int finalPageCount = pageCount;
			addRenderableWidget(new ScdButton(contentX, y, navWidth, 16, Component.literal("< Prev"), ScdTheme.ACCENT_ACCESSORIES, () -> {
				if (page > 0) {
					page--;
					rebuildWidgets();
				}
			}));
			addRenderableWidget(new ScdButton(contentX + navWidth + 8, y, navWidth, 16, Component.literal("Next >"), ScdTheme.ACCENT_ACCESSORIES, () -> {
				if (page < finalPageCount - 1) {
					page++;
					rebuildWidgets();
				}
			}));
			y += 22;
		}

		String toggleLabel = showMissing
				? "Show owned (" + summary.accessoryCount() + ")"
				: "Show missing (" + summary.missingAccessories().size() + ")";
		addRenderableWidget(new ScdButton(contentX, y, fieldWidth, 16, Component.literal(toggleLabel), ScdTheme.ACCENT_ACCESSORIES, () -> {
			showMissing = !showMissing;
			page = 0;
			rebuildWidgets();
		}));
		y += 22;

		addRenderableWidget(new ScdButton(contentX, y, fieldWidth, 16, Component.literal("Refresh"), ScdTheme.ACCENT_ACCESSORIES, client::refreshAccessories));
		y += 22;

		return y;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		if (client.accessoryData().status() != lastSeenStatus) {
			rebuildWidgets();
		}

		ScdTheme.panel(g, panelX, panelY, panelWidth, panelHeight);
		super.extractRenderState(g, mouseX, mouseY, partialTick);

		ScdTheme.label(g, this.font, "Accessories", panelX + PADDING, panelY + 10, ScdTheme.TEXT_PRIMARY);
		ScdTheme.divider(g, panelX + PADDING, panelY + 22, panelWidth - PADDING * 2);

		if (statusLabelY >= 0) {
			ScdTheme.label(g, this.font, statusText(), panelX + PADDING, statusLabelY, ScdTheme.TEXT_SECONDARY);
		}
		if (summaryLabelY >= 0) {
			var summary = client.accessoryData().summary();
			String text;
			if (showMissing) {
				// Naive id diff against Hypixel's own item list - see AccessorySummary's doc comment for
				// the known upgrade-family over-counting caveat (a maxed-out item's lower tiers still
				// show here, since upgrading consumes rather than keeps them).
				text = summary.missingAccessories().size() + " missing (may include already-upgraded lower tiers - see notes)";
			} else {
				// peakMagicalPower is Hypixel's own lifetime-best figure, NOT the current total (confirmed
				// wrong live when first shown as "Magical Power" outright - see ScdApiClient.fetchAccessories)
				// - labelled explicitly as a peak here so it's never mistaken for the live number again.
				String peakSuffix = summary.peakMagicalPower() != null ? " (peak Accessory Power ever: " + summary.peakMagicalPower() + ")" : "";
				text = summary.accessoryCount() + " accessories" + peakSuffix;
			}
			ScdTheme.label(g, this.font, text, panelX + PADDING, summaryLabelY, ScdTheme.TEXT_PRIMARY);
		}
		for (RowLabel row : rowLabels) {
			ScdTheme.label(g, this.font, row.text(), panelX + PADDING, row.y(), row.color());
		}
		ScdTheme.label(g, this.font, "Missing accessories overlay", panelX + PADDING, missingOverlayLabelY, ScdTheme.TEXT_SECONDARY);
		if (pageLabelY >= 0) {
			var summary = client.accessoryData().summary();
			int rowCount = showMissing ? summary.missingAccessories().size() : summary.accessories().size();
			int pageCount = Math.max(1, (rowCount + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
			ScdTheme.scaledCenteredText(g, this.font, Component.literal("Page " + (page + 1) + "/" + pageCount),
					panelX + panelWidth / 2, pageLabelY, ScdTheme.TEXT_MUTED);
		}
	}

	private String statusText() {
		return switch (client.accessoryData().status()) {
			case IDLE, LOADING -> "Loading accessories...";
			case ERROR -> "Failed: " + client.accessoryData().errorMessage();
			case LOADED -> showMissing ? "None missing - you have every accessory!" : "Nothing in your accessory bag.";
		};
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
