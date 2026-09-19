package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists every recorded drop for one Slayer type with a remove button per row,
 * paged rather than scrolled (simpler and just as usable for the handful of
 * distinct items a Slayer grind actually accumulates) - the GUI counterpart
 * to "/scd slayer drops remove", for removing one bad/misattributed entry
 * without memorizing an index from a chat listing.
 */
public class ScdSlayerDropsScreen extends Screen {
	private static final int PANEL_WIDTH = 300;
	private static final int PADDING = 16;
	private static final int ROW_HEIGHT = 18;
	private static final int ROWS_PER_PAGE = 8;

	private record RowLabel(String text, int y) {
	}

	private final Screen parent;
	private final ScdClient client;
	private final ScdSlayerType type;
	private int page = 0;

	private int panelX, panelY, panelWidth, panelHeight;
	private int emptyLabelY = -1;
	private int pageLabelY = -1;
	private final List<RowLabel> rowLabels = new ArrayList<>();

	public ScdSlayerDropsScreen(Screen parent, ScdClient client, ScdSlayerType type) {
		super(Component.literal("SCD - " + type.displayName() + " Drops"));
		this.parent = parent;
		this.client = client;
		this.type = type;
	}

	@Override
	protected void init() {
		rowLabels.clear();
		List<ScdSlayerDrops.Entry> entries = client.slayerDrops().entriesForType(type);
		int pageCount = Math.max(1, (entries.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
		page = Math.max(0, Math.min(page, pageCount - 1));

		panelWidth = PANEL_WIDTH;
		panelX = this.width / 2 - panelWidth / 2;
		panelY = this.height / 2 - 140;
		int contentX = panelX + PADDING;
		int fieldWidth = panelWidth - PADDING * 2;

		int y = panelY + 32;
		emptyLabelY = -1;

		if (entries.isEmpty()) {
			emptyLabelY = y;
			y += ROW_HEIGHT;
		} else {
			int from = page * ROWS_PER_PAGE;
			int to = Math.min(from + ROWS_PER_PAGE, entries.size());
			for (int i = from; i < to; i++) {
				var entry = entries.get(i);
				int rowWidth = fieldWidth - 22;
				rowLabels.add(new RowLabel(entry.displayName() + ": " + ScdFormat.compactCount(entry.count()), y + 3));
				addRenderableWidget(new ScdButton(contentX + rowWidth + 6, y, 16, ROW_HEIGHT - 2, Component.literal("x"), ScdTheme.ACCENT_SLAYER, () -> {
					client.slayerDrops().remove(type, entry.itemId());
					rebuildWidgets();
				}));
				y += ROW_HEIGHT;
			}
		}
		y += 6;

		if (pageCount > 1) {
			// Own row above the buttons - it used to share the button row at the same y as "Next >"
			// and render right on top of it (visible overlap in testing).
			pageLabelY = y;
			y += 10;

			int navWidth = (fieldWidth - 8) / 2;
			int finalPageCount = pageCount;
			addRenderableWidget(new ScdButton(contentX, y, navWidth, 16, Component.literal("< Prev"), ScdTheme.ACCENT_SLAYER, () -> {
				if (page > 0) {
					page--;
					rebuildWidgets();
				}
			}));
			addRenderableWidget(new ScdButton(contentX + navWidth + 8, y, navWidth, 16, Component.literal("Next >"), ScdTheme.ACCENT_SLAYER, () -> {
				if (page < finalPageCount - 1) {
					page++;
					rebuildWidgets();
				}
			}));
			y += 22;
		} else {
			pageLabelY = -1;
		}

		addRenderableWidget(new ScdButton(contentX, y, fieldWidth, 16, Component.literal("Clear all"), ScdTheme.ACCENT_SLAYER, () -> {
			client.slayerDrops().clear(type);
			page = 0;
			rebuildWidgets();
		}));
		y += 22;

		int buttonWidth = (fieldWidth - 8) / 2;
		addRenderableWidget(new ScdButton(contentX, y, buttonWidth, 16, Component.literal("Back"), ScdTheme.ACCENT_SLAYER, () ->
				Minecraft.getInstance().setScreen(parent)));
		addRenderableWidget(new ScdButton(contentX + buttonWidth + 8, y, buttonWidth, 16, Component.literal("Close"), ScdTheme.ACCENT_SLAYER, () ->
				Minecraft.getInstance().setScreen(null)));
		y += 16;

		panelHeight = y + PADDING - panelY;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		ScdTheme.panel(g, panelX, panelY, panelWidth, panelHeight);
		super.extractRenderState(g, mouseX, mouseY, partialTick);

		ScdTheme.label(g, this.font, type.displayName() + " Drops", panelX + PADDING, panelY + 10, ScdTheme.TEXT_PRIMARY);
		ScdTheme.divider(g, panelX + PADDING, panelY + 22, panelWidth - PADDING * 2);

		if (emptyLabelY >= 0) {
			ScdTheme.label(g, this.font, "Nothing recorded yet.", panelX + PADDING, emptyLabelY, ScdTheme.TEXT_SECONDARY);
		}
		for (RowLabel row : rowLabels) {
			ScdTheme.label(g, this.font, row.text(), panelX + PADDING, row.y(), ScdTheme.TEXT_SECONDARY);
		}
		if (pageLabelY >= 0) {
			int pageCount = Math.max(1, (client.slayerDrops().entriesForType(type).size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
			ScdTheme.scaledCenteredText(g, this.font, Component.literal("Page " + (page + 1) + "/" + pageCount),
					panelX + panelWidth / 2, pageLabelY, ScdTheme.TEXT_MUTED);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
