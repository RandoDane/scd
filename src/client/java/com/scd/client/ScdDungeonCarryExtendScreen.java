package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Quick "add more runs to this same deal" form, opened via the "+" button on a dungeon carry's
 * row in ScdDungeonCarryQueueScreen. Mirrors ScdCarryExtendScreen - reuses the entry's own
 * already-agreed price per run instead of asking again.
 */
public class ScdDungeonCarryExtendScreen extends Screen {
	private static final int PANEL_WIDTH = 240;
	private static final int PADDING = 16;

	private final Screen parent;
	private final ScdClient client;
	private final ScdDungeonCarryEntry entry;

	private String runCountDraft = "";
	private EditBox runCountBox;

	private int panelX, panelY, panelWidth, panelHeight;
	private int contextLabelY, priceLabelY, runCountLabelY, errorLabelY;
	private int contentX, fieldWidth;
	private String errorText;

	public ScdDungeonCarryExtendScreen(Screen parent, ScdClient client, ScdDungeonCarryEntry entry) {
		super(Component.literal("SCD - Add More"));
		this.parent = parent;
		this.client = client;
		this.entry = entry;
	}

	@Override
	protected void init() {
		panelWidth = PANEL_WIDTH;
		panelX = this.width / 2 - panelWidth / 2;
		panelY = this.height / 2 - 90;
		contentX = panelX + PADDING;
		fieldWidth = panelWidth - PADDING * 2;

		int y = panelY + 34;

		contextLabelY = y;
		y += 12;

		priceLabelY = y;
		y += 14;

		runCountLabelY = y;
		y += 10;
		runCountBox = new EditBox(this.font, contentX, y, fieldWidth, 14, Component.literal("Additional runs"));
		runCountBox.setBordered(false);
		runCountBox.setTextColor(ScdTheme.TEXT_PRIMARY);
		runCountBox.setHint(Component.literal("Additional runs"));
		runCountBox.setValue(runCountDraft);
		runCountBox.setResponder(s -> {
			restrictToDigits(runCountBox, s);
			runCountDraft = runCountBox.getValue();
		});
		addRenderableWidget(runCountBox);
		y += 20;

		errorLabelY = y;
		y += 14;

		addRenderableWidget(new ScdButton(contentX, y, fieldWidth, 16, Component.literal("Add"), ScdTheme.ACCENT_DUNGEON, this::onAddPressed));
		y += 22;

		int buttonWidth = (fieldWidth - 8) / 2;
		addRenderableWidget(new ScdButton(contentX, y, buttonWidth, 16, Component.literal("Back"), ScdTheme.ACCENT_DUNGEON, () ->
				Minecraft.getInstance().setScreen(parent)));
		addRenderableWidget(new ScdButton(contentX + buttonWidth + 8, y, buttonWidth, 16, Component.literal("Close"), ScdTheme.ACCENT_DUNGEON, () ->
				Minecraft.getInstance().setScreen(null)));
		y += 16;

		panelHeight = y + PADDING - panelY;
	}

	private void onAddPressed() {
		long additional = parseLongOrZero(runCountBox.getValue());
		if (additional <= 0) {
			errorText = "Enter how many more runs.";
			return;
		}
		client.dungeonCarryQueue().extend(entry.id, additional);
		Minecraft.getInstance().setScreen(parent);
	}

	private static void restrictToDigits(EditBox box, String text) {
		String cleaned = text.replaceAll("[^0-9]", "");
		if (!cleaned.equals(text)) box.setValue(cleaned);
	}

	private static long parseLongOrZero(String text) {
		try {
			return text.isBlank() ? 0 : Long.parseLong(text.trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		ScdTheme.panel(g, panelX, panelY, panelWidth, panelHeight);
		super.extractRenderState(g, mouseX, mouseY, partialTick);

		ScdTheme.label(g, this.font, "Add More", panelX + PADDING, panelY + 10, ScdTheme.TEXT_PRIMARY);
		ScdTheme.divider(g, panelX + PADDING, panelY + 22, panelWidth - PADDING * 2);

		ScdTheme.label(g, this.font, entry.playerName + " - " + entry.floor,
				contentX, contextLabelY, ScdTheme.TEXT_SECONDARY);
		ScdTheme.label(g, this.font, "Price per run: " + ScdFormat.coins(entry.pricePerRun), contentX, priceLabelY, ScdTheme.TEXT_MUTED);
		ScdTheme.sectionLabel(g, this.font, "Additional Runs", contentX, runCountLabelY);

		if (errorText != null) {
			ScdTheme.label(g, this.font, errorText, contentX, errorLabelY, ScdTheme.ACCENT_DUNGEON);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
