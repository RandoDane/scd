package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Generic "Customize appearance" screen for any HUD box: a live preview, a text-size slider, a
 * box-size slider (mirrors the corner-drag in ScdHudEditScreen so resizing doesn't require leaving
 * this screen), and one color row per ScdColorSlot the caller passes in.
 *
 * This is the reusable half of SCD's HUD appearance customization (see ScdColorSlot's doc for the
 * other half). To add this to a new HUD: define its own enum implementing ScdColorSlot (one
 * constant per independently-tintable region), add a {@code Map<String, Integer>} colors field and
 * a {@code float} text-scale field to that HUD's config section, thread both through its render code
 * via ScdColorSlot.resolve() and the ScdTheme *(..., scale) overloads, add a
 * renderPreviewAt(graphics, font, x, y) method to that HUD's renderer (see ScdSlayerHud for the
 * pattern - swap the position fields, render, restore in a finally), then open this screen from that
 * HUD's settings screen with its own slot list, color map, Pos, text-scale getter/setter and
 * PositionedPreviewRenderer. See ScdSlayerConfigScreen's "Customize appearance..." button for a
 * complete worked example.
 */
public class ScdHudAppearanceScreen extends Screen {
	@FunctionalInterface
	public interface PositionedPreviewRenderer {
		ScdOverlayBox.Bounds render(GuiGraphicsExtractor graphics, Font font, int x, int y);
	}

	private static final int PANEL_WIDTH = 300;
	private static final int PADDING = 16;
	private static final int PREVIEW_WIDTH = 260;
	private static final int PREVIEW_GAP = 20;
	private static final int ROW_HEIGHT = 20;
	private static final int SWATCH_SIZE = 14;
	private static final int HEX_BOX_WIDTH = 64;

	// Kept tight (see ScdTheme.TEXT_SCALE's own doc) - vanilla's font is a small pixel bitmap sampled
	// nearest-neighbor, so a scale far from 1.0 reintroduces uneven, "ugly" stroke widths. This range
	// nudges size without going far enough to look chunky.
	private static final float MIN_TEXT_SCALE = 0.85f;
	private static final float MAX_TEXT_SCALE = 1.3f;
	private static final float TEXT_SCALE_STEP = 0.05f;

	private record ColorRow(ScdColorSlot slot, int labelX, int labelY, int swatchX, int swatchY) {
	}

	private final Screen parent;
	private final ScdConfig config;
	private final String hudName;
	private final int accentColor;
	private final List<? extends ScdColorSlot> slots;
	private final Map<String, Integer> colorOverrides;
	private final ScdConfig.Pos boxPosition;
	private final Supplier<Float> textScaleGetter;
	private final Consumer<Float> textScaleSetter;
	private final PositionedPreviewRenderer previewRenderer;

	private final List<ColorRow> colorRows = new ArrayList<>();
	private int panelX, panelY, panelWidth, panelHeight;
	private int previewX, previewY;

	public ScdHudAppearanceScreen(Screen parent, ScdConfig config, String hudName, int accentColor,
			List<? extends ScdColorSlot> slots, Map<String, Integer> colorOverrides, ScdConfig.Pos boxPosition,
			Supplier<Float> textScaleGetter, Consumer<Float> textScaleSetter, PositionedPreviewRenderer previewRenderer) {
		super(Component.literal("SCD - " + hudName + " Appearance"));
		this.parent = parent;
		this.config = config;
		this.hudName = hudName;
		this.accentColor = accentColor;
		this.slots = slots;
		this.colorOverrides = colorOverrides;
		this.boxPosition = boxPosition;
		this.textScaleGetter = textScaleGetter;
		this.textScaleSetter = textScaleSetter;
		this.previewRenderer = previewRenderer;
	}

	@Override
	protected void init() {
		colorRows.clear();
		panelWidth = PANEL_WIDTH;
		// Preview sits in its own column to the left of the settings panel instead of stacked inside
		// it - the settings column's PREVIEW_HEIGHT used to be a fixed guess, but the actual HUD can
		// render taller than that (bigger box/text size, more lines active), and it drew right over
		// the sliders/color rows below it when it did. An open column has no such ceiling.
		int totalWidth = PREVIEW_WIDTH + PREVIEW_GAP + panelWidth;
		int leftX = this.width / 2 - totalWidth / 2;
		int previewAreaX = leftX;
		panelX = leftX + PREVIEW_WIDTH + PREVIEW_GAP;
		panelY = 20;
		int contentX = panelX + PADDING;
		int fieldWidth = panelWidth - PADDING * 2;

		previewX = previewAreaX + 10;
		previewY = panelY + 32;

		int y = panelY + 32;

		addRenderableWidget(new ScdSlider(contentX, y, fieldWidth, 16, MIN_TEXT_SCALE, MAX_TEXT_SCALE, TEXT_SCALE_STEP,
				textScaleGetter.get(), accentColor, v -> "Text Size: " + Math.round(v * 100) + "%", textScaleSetter::accept));
		y += ROW_HEIGHT + 8;

		addRenderableWidget(new ScdSlider(contentX, y, fieldWidth, 16, ScdHudEditScreen.MIN_SCALE, ScdHudEditScreen.MAX_SCALE, 0.1f,
				boxPosition.scale, accentColor, v -> "Box Size: " + Math.round(v * 100) + "%", v -> boxPosition.scale = v));
		y += ROW_HEIGHT + 14;

		for (ScdColorSlot slot : slots) {
			int labelY = y + 3;
			int hexX = contentX + fieldWidth - HEX_BOX_WIDTH;
			int swatchX = hexX - SWATCH_SIZE - 6;

			EditBox hexBox = new EditBox(this.font, hexX, y, HEX_BOX_WIDTH, 14, Component.literal(slot.label()));
			hexBox.setBordered(false);
			hexBox.setTextColor(ScdTheme.TEXT_PRIMARY);
			hexBox.setMaxLength(7);
			hexBox.setValue(String.format(Locale.ROOT, "#%06X", ScdColorSlot.resolve(colorOverrides, slot) & 0xFFFFFF));
			hexBox.setResponder(s -> onHexChanged(slot, hexBox, s));
			addRenderableWidget(hexBox);

			colorRows.add(new ColorRow(slot, contentX, labelY, swatchX, y));
			y += ROW_HEIGHT;
		}
		y += 4;

		addRenderableWidget(new ScdButton(contentX, y, fieldWidth, 16, Component.literal("Reset appearance to defaults"), accentColor, this::resetAll));
		y += 22;

		int buttonWidth = (fieldWidth - 8) / 2;
		addRenderableWidget(new ScdButton(contentX, y, buttonWidth, 16, Component.literal("Back"), accentColor, () -> {
			config.save();
			Minecraft.getInstance().setScreen(parent);
		}));
		addRenderableWidget(new ScdButton(contentX + buttonWidth + 8, y, buttonWidth, 16, Component.literal("Close"), accentColor, () -> {
			config.save();
			Minecraft.getInstance().setScreen(null);
		}));
		y += 16;

		panelHeight = y + PADDING - panelY;
	}

	/** EditBox has no built-in input filter - strips invalid chars live, then commits once 6 valid hex digits are typed. */
	private void onHexChanged(ScdColorSlot slot, EditBox box, String text) {
		String cleaned = text.replaceAll("[^0-9a-fA-F#]", "");
		if (!cleaned.equals(text)) {
			box.setValue(cleaned);
			return;
		}
		String digits = cleaned.startsWith("#") ? cleaned.substring(1) : cleaned;
		if (digits.isEmpty()) {
			colorOverrides.remove(slot.id());
			return;
		}
		if (digits.length() != 6) return;
		try {
			colorOverrides.put(slot.id(), 0xFF000000 | Integer.parseInt(digits, 16));
		} catch (NumberFormatException ignored) {
			// Leave whatever was last valid in place until the player finishes typing a valid value.
		}
	}

	private void resetAll() {
		colorOverrides.clear();
		boxPosition.scale = 1.0f;
		textScaleSetter.accept(1.0f);
		rebuildWidgets();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		// Panel must be drawn before the super call, same reasoning as every other SCD screen - see
		// ScdSlayerConfigScreen for the long-form explanation.
		ScdTheme.panel(g, panelX, panelY, panelWidth, panelHeight);
		super.extractRenderState(g, mouseX, mouseY, partialTick);

		ScdTheme.label(g, this.font, hudName + " Appearance", panelX + PADDING, panelY + 10, ScdTheme.TEXT_PRIMARY);
		ScdTheme.divider(g, panelX + PADDING, panelY + 22, panelWidth - PADDING * 2);

		ScdTheme.label(g, this.font, "Preview", previewX, previewY - 12, ScdTheme.TEXT_SECONDARY);
		var drawn = previewRenderer.render(g, this.font, previewX, previewY);
		if (drawn == null) {
			ScdTheme.label(g, this.font, "(nothing to preview right now)", previewX + 4, previewY + 4, ScdTheme.TEXT_SECONDARY);
		}

		for (ColorRow row : colorRows) {
			ScdTheme.label(g, this.font, row.slot().label(), row.labelX(), row.labelY(), ScdTheme.TEXT_SECONDARY);
			int color = ScdColorSlot.resolve(colorOverrides, row.slot());
			g.fill(row.swatchX(), row.swatchY(), row.swatchX() + SWATCH_SIZE, row.swatchY() + SWATCH_SIZE, 0xFF000000 | color);
			g.outline(row.swatchX(), row.swatchY(), SWATCH_SIZE, SWATCH_SIZE, ScdTheme.PANEL_BORDER);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
