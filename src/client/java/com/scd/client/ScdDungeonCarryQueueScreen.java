package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists every dungeon carry entry (active first, then completed), paged like ScdCarryQueueScreen
 * - a "Done" button on an active row closes it out manually, "x" removes it outright.
 */
public class ScdDungeonCarryQueueScreen extends Screen {
	private static final int PANEL_WIDTH = 300;
	private static final int PADDING = 16;
	private static final int ROW_HEIGHT = 18;
	private static final int ROWS_PER_PAGE = 6;

	private record RowLabel(String text, int y, boolean active) {
	}

	private final Screen parent;
	private final ScdClient client;
	private int page = 0;

	private int panelX, panelY, panelWidth, panelHeight;
	private int emptyLabelY = -1;
	private int pageLabelY = -1;
	private final List<RowLabel> rowLabels = new ArrayList<>();

	public ScdDungeonCarryQueueScreen(Screen parent, ScdClient client) {
		super(Component.literal("SCD - Dungeon Carries"));
		this.parent = parent;
		this.client = client;
	}

	@Override
	protected void init() {
		rowLabels.clear();
		List<ScdDungeonCarryEntry> entries = client.dungeonCarryQueue().all();
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
				String progress = entry.isActive()
						? entry.runsCompleted + "/" + entry.runsOwed
						: "done";
				String text = entry.playerName + " - " + entry.floor + " (" + progress + ")";
				rowLabels.add(new RowLabel(text, y + 3, entry.isActive()));

				int buttonsWidth = entry.isActive() ? 58 : 36;
				int rowWidth = fieldWidth - buttonsWidth - 4;
				int bx = contentX + rowWidth + 6;
				if (entry.isActive()) {
					addRenderableWidget(new ScdButton(bx, y, 20, ROW_HEIGHT - 2, Component.literal("Done"), ScdTheme.ACCENT_DUNGEON, () -> {
						client.finishDungeonCarryManually(entry);
						rebuildWidgets();
					}));
					bx += 22;
				}
				addRenderableWidget(new ScdButton(bx, y, 16, ROW_HEIGHT - 2, Component.literal("+"), ScdTheme.ACCENT_DUNGEON, () ->
						Minecraft.getInstance().setScreen(new ScdDungeonCarryExtendScreen(this, client, entry))));
				bx += 18;
				addRenderableWidget(new ScdButton(bx, y, 16, ROW_HEIGHT - 2, Component.literal("x"), ScdTheme.ACCENT_DUNGEON, () -> {
					client.dungeonCarryQueue().remove(entry.id);
					rebuildWidgets();
				}));
				y += ROW_HEIGHT;
			}
		}
		y += 6;

		if (pageCount > 1) {
			pageLabelY = y;
			y += 10;

			int navWidth = (fieldWidth - 8) / 2;
			int finalPageCount = pageCount;
			addRenderableWidget(new ScdButton(contentX, y, navWidth, 16, Component.literal("< Prev"), ScdTheme.ACCENT_DUNGEON, () -> {
				if (page > 0) {
					page--;
					rebuildWidgets();
				}
			}));
			addRenderableWidget(new ScdButton(contentX + navWidth + 8, y, navWidth, 16, Component.literal("Next >"), ScdTheme.ACCENT_DUNGEON, () -> {
				if (page < finalPageCount - 1) {
					page++;
					rebuildWidgets();
				}
			}));
			y += 22;
		} else {
			pageLabelY = -1;
		}

		addRenderableWidget(new ScdButton(contentX, y, fieldWidth, 16, Component.literal("Add carry..."), ScdTheme.ACCENT_DUNGEON, () ->
				Minecraft.getInstance().setScreen(new ScdDungeonCarryFormScreen(this, client))));
		y += 22;

		int buttonWidth = (fieldWidth - 8) / 2;
		addRenderableWidget(new ScdButton(contentX, y, buttonWidth, 16, Component.literal("Back"), ScdTheme.ACCENT_DUNGEON, () ->
				Minecraft.getInstance().setScreen(parent)));
		addRenderableWidget(new ScdButton(contentX + buttonWidth + 8, y, buttonWidth, 16, Component.literal("Close"), ScdTheme.ACCENT_DUNGEON, () ->
				Minecraft.getInstance().setScreen(null)));
		y += 16;

		panelHeight = y + PADDING - panelY;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		ScdTheme.panel(g, panelX, panelY, panelWidth, panelHeight);
		super.extractRenderState(g, mouseX, mouseY, partialTick);

		ScdTheme.label(g, this.font, "Dungeon Carries", panelX + PADDING, panelY + 10, ScdTheme.TEXT_PRIMARY);
		ScdTheme.divider(g, panelX + PADDING, panelY + 22, panelWidth - PADDING * 2);

		if (emptyLabelY >= 0) {
			ScdTheme.label(g, this.font, "No dungeon carries yet.", panelX + PADDING, emptyLabelY, ScdTheme.TEXT_SECONDARY);
		}
		for (RowLabel row : rowLabels) {
			ScdTheme.label(g, this.font, row.text(), panelX + PADDING, row.y(), row.active() ? ScdTheme.TEXT_SECONDARY : ScdTheme.TEXT_MUTED);
		}
		if (pageLabelY >= 0) {
			int pageCount = Math.max(1, (client.dungeonCarryQueue().all().size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
			ScdTheme.scaledCenteredText(g, this.font, Component.literal("Page " + (page + 1) + "/" + pageCount),
					panelX + panelWidth / 2, pageLabelY, ScdTheme.TEXT_MUTED);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
