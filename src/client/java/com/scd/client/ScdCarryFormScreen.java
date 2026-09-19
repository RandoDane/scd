package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Pop-up form for starting a new Slayer carry: player name, type, tier, price
 * per kill, and total amount agreed. Kills owed is derived live from the two
 * price fields rather than entered directly - if it doesn't divide evenly,
 * the leftover coin remainder is shown next to it instead of silently
 * rounding. Type/tier are cycled with arrow buttons rather than a real
 * dropdown widget (none exists in this project yet) - same interaction as
 * the drops screen's page nav.
 */
public class ScdCarryFormScreen extends Screen {
	private static final String[] TIERS = {"I", "II", "III", "IV", "V"};
	private static final int PANEL_WIDTH = 260;
	private static final int PADDING = 16;
	private static final int ARROW_WIDTH = 16;
	private static final int ROW_HEIGHT = 16;

	private final Screen parent;
	private final ScdClient client;

	private int typeIndex = 0;
	private int tierIndex = 2; // III - the most commonly carried tier
	private EditBox playerNameBox;
	private EditBox pricePerKillBox;
	private EditBox totalAmountBox;

	private int panelX, panelY, panelWidth, panelHeight;
	private int playerLabelY, typeRowY, tierRowY, priceLabelY, totalLabelY, owedLabelY, errorLabelY;
	private int rowContentX, rowFieldWidth;
	private String errorText;

	public ScdCarryFormScreen(Screen parent, ScdClient client) {
		super(Component.literal("SCD - New Carry"));
		this.parent = parent;
		this.client = client;
	}

	@Override
	protected void init() {
		panelWidth = PANEL_WIDTH;
		panelX = this.width / 2 - panelWidth / 2;
		panelY = this.height / 2 - 115;
		int contentX = panelX + PADDING;
		int fieldWidth = panelWidth - PADDING * 2;
		rowContentX = contentX;
		rowFieldWidth = fieldWidth;

		int y = panelY + 32;

		playerLabelY = y;
		y += 10;
		playerNameBox = new EditBox(this.font, contentX, y, fieldWidth, 14, Component.literal("Player name"));
		playerNameBox.setBordered(false);
		playerNameBox.setTextColor(ScdTheme.TEXT_PRIMARY);
		playerNameBox.setHint(Component.literal("Player name"));
		addRenderableWidget(playerNameBox);
		y += 20;

		typeRowY = y;
		addRenderableWidget(new ScdButton(contentX, y, ARROW_WIDTH, ROW_HEIGHT, Component.literal("<"), ScdTheme.ACCENT_SLAYER,
				() -> typeIndex = Math.floorMod(typeIndex - 1, ScdSlayerType.values().length)));
		addRenderableWidget(new ScdButton(contentX + fieldWidth - ARROW_WIDTH, y, ARROW_WIDTH, ROW_HEIGHT, Component.literal(">"), ScdTheme.ACCENT_SLAYER,
				() -> typeIndex = Math.floorMod(typeIndex + 1, ScdSlayerType.values().length)));
		y += ROW_HEIGHT + 6;

		tierRowY = y;
		addRenderableWidget(new ScdButton(contentX, y, ARROW_WIDTH, ROW_HEIGHT, Component.literal("<"), ScdTheme.ACCENT_SLAYER,
				() -> tierIndex = Math.floorMod(tierIndex - 1, TIERS.length)));
		addRenderableWidget(new ScdButton(contentX + fieldWidth - ARROW_WIDTH, y, ARROW_WIDTH, ROW_HEIGHT, Component.literal(">"), ScdTheme.ACCENT_SLAYER,
				() -> tierIndex = Math.floorMod(tierIndex + 1, TIERS.length)));
		y += ROW_HEIGHT + 10;

		priceLabelY = y;
		y += 10;
		pricePerKillBox = new EditBox(this.font, contentX, y, fieldWidth, 14, Component.literal("Price per kill"));
		pricePerKillBox.setBordered(false);
		pricePerKillBox.setTextColor(ScdTheme.TEXT_PRIMARY);
		pricePerKillBox.setHint(Component.literal("Price per kill"));
		pricePerKillBox.setResponder(s -> restrictToDigits(pricePerKillBox, s));
		addRenderableWidget(pricePerKillBox);
		y += 20;

		totalLabelY = y;
		y += 10;
		totalAmountBox = new EditBox(this.font, contentX, y, fieldWidth, 14, Component.literal("Total amount"));
		totalAmountBox.setBordered(false);
		totalAmountBox.setTextColor(ScdTheme.TEXT_PRIMARY);
		totalAmountBox.setHint(Component.literal("Total amount"));
		totalAmountBox.setResponder(s -> restrictToDigits(totalAmountBox, s));
		addRenderableWidget(totalAmountBox);
		y += 20;

		owedLabelY = y;
		y += 14;

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
		String playerName = playerNameBox.getValue().trim();
		if (playerName.isEmpty()) {
			errorText = "Enter a player name.";
			return;
		}
		long pricePerKill = parseLongOrZero(pricePerKillBox.getValue());
		long totalAmount = parseLongOrZero(totalAmountBox.getValue());
		if (pricePerKill <= 0 || totalAmount <= 0) {
			errorText = "Enter a price per kill and total amount.";
			return;
		}
		if (totalAmount < pricePerKill) {
			errorText = "Total amount is less than one kill's price.";
			return;
		}

		ScdSlayerType type = ScdSlayerType.values()[typeIndex];
		String tier = TIERS[tierIndex];
		client.carryQueue().add(playerName, type, tier, pricePerKill, totalAmount);
		Minecraft.getInstance().setScreen(new ScdCarryQueueScreen(parent, client));
	}

	/** EditBox has no built-in input filter in this API - strips non-digits on every keystroke instead. */
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

		ScdTheme.label(g, this.font, "New Carry", panelX + PADDING, panelY + 10, ScdTheme.TEXT_PRIMARY);
		ScdTheme.divider(g, panelX + PADDING, panelY + 22, panelWidth - PADDING * 2);

		ScdTheme.sectionLabel(g, this.font, "Player Name", rowContentX, playerLabelY);

		int rowCenterX = rowContentX + rowFieldWidth / 2;
		ScdTheme.scaledCenteredText(g, this.font, Component.literal(ScdSlayerType.values()[typeIndex].displayName()),
				rowCenterX, typeRowY + 4, ScdTheme.TEXT_PRIMARY);
		ScdTheme.scaledCenteredText(g, this.font, Component.literal("Tier " + TIERS[tierIndex]),
				rowCenterX, tierRowY + 4, ScdTheme.TEXT_PRIMARY);

		ScdTheme.sectionLabel(g, this.font, "Price Per Kill", rowContentX, priceLabelY);
		ScdTheme.sectionLabel(g, this.font, "Total Amount", rowContentX, totalLabelY);

		long pricePerKill = parseLongOrZero(pricePerKillBox.getValue());
		long totalAmount = parseLongOrZero(totalAmountBox.getValue());
		String owedText;
		if (pricePerKill > 0 && totalAmount > 0) {
			long owed = totalAmount / pricePerKill;
			long leftover = totalAmount % pricePerKill;
			owedText = "Kills owed: " + owed + (leftover > 0 ? " (+" + ScdFormat.coins(leftover) + " left over)" : "");
		} else {
			owedText = "Kills owed: -";
		}
		ScdTheme.label(g, this.font, owedText, rowContentX, owedLabelY, ScdTheme.TEXT_SECONDARY);

		if (errorText != null) {
			ScdTheme.label(g, this.font, errorText, rowContentX, errorLabelY, ScdTheme.ACCENT_SLAYER);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
