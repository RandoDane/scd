package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** A pill-shaped on/off switch, used everywhere in SCD's own screens instead of vanilla's plain Checkbox. */
public class ScdToggle extends AbstractWidget {
	@FunctionalInterface
	public interface OnChange {
		void onChange(boolean value);
	}

	private static final int TRACK_WIDTH = 26;
	private static final int TRACK_HEIGHT = 12;

	private final int onColor;
	private final OnChange onChange;
	private boolean value;

	public ScdToggle(int x, int y, Component label, int onColor, boolean initial, OnChange onChange) {
		super(x, y, TRACK_WIDTH, TRACK_HEIGHT, label);
		this.onColor = onColor;
		this.value = initial;
		this.onChange = onChange;
	}

	public boolean value() {
		return value;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		value = !value;
		playDownSound(Minecraft.getInstance().getSoundManager());
		onChange.onChange(value);
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		int x = getX(), y = getY(), w = getWidth(), h = getHeight();

		int trackColor = value ? onColor : ScdTheme.TRACK_OFF;
		g.fill(x, y, x + w, y + h, trackColor);
		g.outline(x, y, w, h, isHovered() ? ScdTheme.TEXT_SECONDARY : ScdTheme.PANEL_BORDER);

		int knobSize = h - 3;
		int knobX = value ? x + w - knobSize - 1 : x + 1;
		g.fill(knobX, y + 1, knobX + knobSize, y + 1 + knobSize, ScdTheme.KNOB);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
