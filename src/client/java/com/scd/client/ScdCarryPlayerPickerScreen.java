package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * Paged list of every player currently on the server (from the tab list),
 * for a "New Carry" form - picking a row fills the form's player name
 * field with the exact-cased IGN instead of relying on typing it correctly,
 * same paging style as ScdSlayerDropsScreen/ScdCarryQueueScreen. Generalized
 * 2026-09-23 (was Slayer-carry-specific, taking a concrete ScdCarryFormScreen)
 * to a plain callback + back-target screen, so ScdDungeonCarryFormScreen can
 * reuse it too - choosing an online player is identical either way.
 */
public class ScdCarryPlayerPickerScreen extends Screen {
	private static final int PANEL_WIDTH = 240;
	private static final int PADDING = 16;
	private static final int ROW_HEIGHT = 18;
	private static final int ROWS_PER_PAGE = 8;

	private final Screen backTarget;
	private final Consumer<String> onPicked;
	private final ScdClient client;
	private int page = 0;

	private int panelX, panelY, panelWidth, panelHeight;
	private int emptyLabelY = -1;
	private int pageLabelY = -1;

	public ScdCarryPlayerPickerScreen(Screen backTarget, ScdClient client, Consumer<String> onPicked) {
		super(Component.literal("SCD - Choose Player"));
		this.backTarget = backTarget;
		this.client = client;
		this.onPicked = onPicked;
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
					onPicked.accept(name);
					Minecraft.getInstance().setScreen(backTarget);
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

		addRenderableWidget(new ScdButton(contentX, y, fieldWidth, 16, Component.literal("Cancel"), ScdTheme.ACCENT_SLAYER, () ->
				Minecraft.getInstance().setScreen(backTarget)));
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
			ScdTheme.scaledCenteredText(g, this.font, Component.literal("Page " + (page + 1) + "/" + pageCount),
					panelX + panelWidth / 2, pageLabelY, ScdTheme.TEXT_MUTED);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
