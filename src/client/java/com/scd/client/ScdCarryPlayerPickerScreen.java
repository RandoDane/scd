package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Paged list of every player currently on the server (from the tab list),
 * for the "New Carry" form - picking a row fills the form's player name
 * field with the exact-cased IGN instead of relying on typing it correctly,
 * same paging style as ScdSlayerDropsScreen/ScdCarryQueueScreen.
 */
public class ScdCarryPlayerPickerScreen extends Screen {
	private static final int PANEL_WIDTH = 240;
	private static final int PADDING = 16;
	private static final int ROW_HEIGHT = 18;
	private static final int ROWS_PER_PAGE = 8;

	private final ScdCarryFormScreen formScreen;
	private final ScdClient client;
	private int page = 0;

	private int panelX, panelY, panelWidth, panelHeight;
	private int emptyLabelY = -1;
	private int pageLabelY = -1;

	public ScdCarryPlayerPickerScreen(ScdCarryFormScreen formScreen, ScdClient client) {
		super(Component.literal("SCD - Choose Player"));
		this.formScreen = formScreen;
		this.client = client;
	}

	@Override
	protected void init() {
		List<String> names = client.onlinePlayerNames();
		int pageCount = Math.max(1, (names.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
		page = Math.max(0, Math.min(page, pageCount - 1));

		panelWidth = PANEL_WIDTH;
		panelX = this.width / 2 - panelWidth / 2;
		panelY = this.height / 2 - 130;
		int contentX = panelX + PADDING;
		int fieldWidth = panelWidth - PADDING * 2;

		int y = panelY + 32;
		emptyLabelY = -1;

		if (names.isEmpty()) {
			emptyLabelY = y;
			y += ROW_HEIGHT;
		} else {
			int from = page * ROWS_PER_PAGE;
			int to = Math.min(from + ROWS_PER_PAGE, names.size());
			for (int i = from; i < to; i++) {
				String name = names.get(i);
				addRenderableWidget(new ScdButton(contentX, y, fieldWidth, ROW_HEIGHT - 2, Component.literal(name), ScdTheme.ACCENT_SLAYER, () -> {
					formScreen.setPlayerName(name);
					Minecraft.getInstance().setScreen(formScreen);
				}));
				y += ROW_HEIGHT;
			}
		}
		y += 6;

		if (pageCount > 1) {
			int navWidth = (fieldWidth - 8) / 2;
			pageLabelY = y + 3;
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

		addRenderableWidget(new ScdButton(contentX, y, fieldWidth, 16, Component.literal("Cancel"), ScdTheme.ACCENT_SLAYER, () ->
				Minecraft.getInstance().setScreen(formScreen)));
		y += 16;

		panelHeight = y + PADDING - panelY;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		ScdTheme.panel(g, panelX, panelY, panelWidth, panelHeight);
		super.extractRenderState(g, mouseX, mouseY, partialTick);

		ScdTheme.label(g, this.font, "Choose Player", panelX + PADDING, panelY + 10, ScdTheme.TEXT_PRIMARY);
		ScdTheme.divider(g, panelX + PADDING, panelY + 22, panelWidth - PADDING * 2);

		if (emptyLabelY >= 0) {
			ScdTheme.label(g, this.font, "No other players found on the tab list.", panelX + PADDING, emptyLabelY, ScdTheme.TEXT_SECONDARY);
		}
		if (pageLabelY >= 0) {
			int pageCount = Math.max(1, (client.onlinePlayerNames().size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
			ScdTheme.label(g, this.font, "Page " + (page + 1) + "/" + pageCount, panelX + panelWidth - PADDING - 50, pageLabelY, ScdTheme.TEXT_MUTED);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
