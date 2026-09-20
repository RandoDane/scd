package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.function.Function;

/**
 * A flat draggable slider over a float range, snapped to a fixed step - used instead of vanilla's
 * AbstractSliderButton for the same reason ScdButton/ScdToggle exist (matching SCD's own flat-card
 * look). Click anywhere on the track to jump there, or drag the same way any other widget here
 * handles a drag: by overriding onClick/onDrag rather than the raw mouse events, so Screen's normal
 * click-then-drag focus handling does the rest for free. The current value is rendered centered on
 * the track via {@code formatter}, so this single widget needs no separate value label.
 */
public class ScdSlider extends AbstractWidget {
	@FunctionalInterface
	public interface OnChange {
		void onChange(float value);
	}

	private final float min;
	private final float max;
	private final float step;
	private final int accentColor;
	private final Function<Float, String> formatter;
	private final OnChange onChange;
	private float value;

	public ScdSlider(int x, int y, int width, int height, float min, float max, float step, float initial,
			int accentColor, Function<Float, String> formatter, OnChange onChange) {
		super(x, y, width, height, Component.empty());
		this.min = min;
		this.max = max;
		this.step = step;
		this.accentColor = accentColor;
		this.formatter = formatter;
		this.onChange = onChange;
		this.value = snap(initial);
	}

	public float value() {
		return value;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		setFromMouseX(event.x());
	}

	@Override
	protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
		setFromMouseX(event.x());
	}

	private void setFromMouseX(double mouseX) {
		double frac = (mouseX - getX()) / (double) getWidth();
		frac = Math.max(0, Math.min(1, frac));
		float newValue = snap((float) (min + frac * (max - min)));
		if (newValue != value) {
			value = newValue;
			onChange.onChange(value);
		}
	}

	private float snap(float v) {
		float snapped = min + Math.round((v - min) / step) * step;
		return Math.max(min, Math.min(max, snapped));
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		int x = getX(), y = getY(), w = getWidth(), h = getHeight();

		g.fill(x, y, x + w, y + h, ScdTheme.TRACK_OFF);
		double frac = (value - min) / (max - min);
		int fillWidth = Math.round((float) (w * frac));
		if (fillWidth > 0) g.fill(x, y, x + fillWidth, y + h, accentColor);
		g.outline(x, y, w, h, isHovered() ? ScdTheme.TEXT_SECONDARY : ScdTheme.PANEL_BORDER);

		var font = Minecraft.getInstance().font;
		String text = formatter.apply(value);
		ScdTheme.scaledCenteredText(g, font, Component.literal(text), x + w / 2, y + (h - ScdTheme.lineHeight(font)) / 2, ScdTheme.TEXT_PRIMARY);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
