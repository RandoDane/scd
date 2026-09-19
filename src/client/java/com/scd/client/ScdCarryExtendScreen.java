package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Quick "add more bosses to this same deal" form, opened via the "+" button
 * on a carry's row in ScdCarryQueueScreen. Reuses the entry's own already-
 * agreed price per kill instead of asking for it again - a repeat customer
 * is almost always still at the same price, and this is for extending the
 * same relationship, not starting a fresh negotiation (use "Add carry..."
 * for that).
 */
public class ScdCarryExtendScreen extends Screen {
	private static final int PANEL_WIDTH = 240;
	private static final int PADDING = 16;

	private final Screen parent;
	private final ScdClient client;
	private final ScdCarryEntry entry;

	private String bossCountDraft = "";
	private EditBox bossCountBox;

	private int panelX, panelY, panelWidth, panelHeight;
	private int contextLabelY, priceLabelY, bossCountLabelY, errorLabelY;
	private int contentX, fieldWidth;
	private String errorText;

	public ScdCarryExtendScreen(Screen parent, ScdClient client, ScdCarryEntry entry) {
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

		bossCountLabelY = y;
		y += 10;
		bossCountBox = new EditBox(this.font, contentX, y, fieldWidth, 14, Component.literal("Additional bosses"));
		bossCountBox.setBordered(false);
		bossCountBox.setTextColor(ScdTheme.TEXT_PRIMARY);
		bossCountBox.setHint(Component.literal("Additional bosses"));
		bossCountBox.setValue(bossCountDraft);
		bossCountBox.setResponder(s -> {
			restrictToDigits(bossCountBox, s);
			bossCountDraft = bossCountBox.getValue();
		});
		addRenderableWidget(bossCountBox);
		y += 20;

		errorLabelY = y;
		y += 14;

		addRenderableWidget(new ScdButton(contentX, y, fieldWidth, 16, Component.literal("Add"), ScdTheme.ACCENT_SLAYER, this::onAddPressed));
		y += 22;

		int buttonWidth = (fieldWidth - 8) / 2;
		addRenderableWidget(new ScdButton(contentX, y, buttonWidth, 16, Component.literal("Back"), ScdTheme.ACCENT_SLAYER, () ->
				Minecraft.getInstance().setScreen(parent)));
		addRenderableWidget(new ScdButton(contentX + buttonWidth + 8, y, buttonWidth, 16, Component.literal("Close"), ScdTheme.ACCENT_SLAYER, () ->
				Minecraft.getInstance().setScreen(null)));
		y += 16;

		panelHeight = y + PADDING - panelY;
	}

	private void onAddPressed() {
		long additional = parseLongOrZero(bossCountBox.getValue());
		if (additional <= 0) {
			errorText = "Enter how many more bosses.";
			return;
		}
		client.carryQueue().extend(entry.id, additional);
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

		ScdTheme.label(g, this.font, entry.playerName + " - " + entry.typeEnum().displayName() + " " + entry.tier,
				contentX, contextLabelY, ScdTheme.TEXT_SECONDARY);
		ScdTheme.label(g, this.font, "Price per kill: " + ScdFormat.coins(entry.pricePerKill), contentX, priceLabelY, ScdTheme.TEXT_MUTED);
		ScdTheme.sectionLabel(g, this.font, "Additional Bosses", contentX, bossCountLabelY);

		if (errorText != null) {
			ScdTheme.label(g, this.font, errorText, contentX, errorLabelY, ScdTheme.ACCENT_SLAYER);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
