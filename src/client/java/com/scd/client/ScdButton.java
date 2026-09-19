package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** A flat, gradient-and-accent styled button, used everywhere in SCD's own screens instead of vanilla's beveled Button. */
public class ScdButton extends AbstractWidget {
	@FunctionalInterface
	public interface OnPress {
		void onPress();
	}

	private final int accentColor;
	private final OnPress onPress;

	public ScdButton(int x, int y, int width, int height, Component message, int accentColor, OnPress onPress) {
		super(x, y, width, height, message);
		this.accentColor = accentColor;
		this.onPress = onPress;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		if (!isActive()) return;
		playDownSound(Minecraft.getInstance().getSoundManager());
		onPress.onPress();
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		int x = getX(), y = getY(), w = getWidth(), h = getHeight();
		boolean hovered = isActive() && isHovered();

		// No drop shadow here - this widget is always placed inside an already-shadowed
		// panel, and a second shadow per button just smears into its neighbors.
		int top = isActive() ? (hovered ? ScdTheme.CARD_HOVER_TOP : ScdTheme.CARD_TOP) : ScdTheme.DISABLED_TOP;
		int bottom = isActive() ? (hovered ? ScdTheme.CARD_HOVER_BOTTOM : ScdTheme.CARD_BOTTOM) : ScdTheme.DISABLED_BOTTOM;
		g.fillGradient(x, y, x + w, y + h, top, bottom);
		g.outline(x, y, w, h, hovered ? accentColor : ScdTheme.PANEL_BORDER);

		var font = Minecraft.getInstance().font;
		int textColor = isActive() ? ScdTheme.TEXT_PRIMARY : ScdTheme.TEXT_MUTED;
		ScdTheme.scaledCenteredText(g, font, getMessage(), x + w / 2, y + (h - ScdTheme.lineHeight(font)) / 2, textColor);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
