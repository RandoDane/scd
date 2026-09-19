package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Lets the player drag a movable HUD box wherever they want, over the live
 * game view. Generic over which box: every feature category with its own
 * draggable overlay (Bazaar's price graph, Slayer's boss tracker, ...) reuses
 * this same screen by passing its own position and preview renderer.
 */
public class ScdHudEditScreen extends Screen {
	@FunctionalInterface
	public interface PreviewRenderer {
		ScdOverlayBox.Bounds render(GuiGraphicsExtractor graphics, Font font);
	}

	private static final ScdOverlayBox.Bounds FALLBACK = new ScdOverlayBox.Bounds(0, 0, 180, 95);

	private final Screen parent;
	private final ScdConfig config;
	private final ScdConfig.Pos position;
	private final PreviewRenderer renderer;
	private final int accentColor;

	private ScdOverlayBox.Bounds bounds;
	private boolean dragging;
	private double dragOffsetX;
	private double dragOffsetY;

	public ScdHudEditScreen(Screen parent, ScdConfig config, ScdConfig.Pos position, int accentColor, PreviewRenderer renderer) {
		super(Component.literal("Move SCD HUD"));
		this.parent = parent;
		this.config = config;
		this.position = position;
		this.accentColor = accentColor;
		this.renderer = renderer;
	}

	@Override
	protected void init() {
		addRenderableWidget(new ScdButton(this.width / 2 - 124, this.height - 28, 120, 16,
				Component.literal("Reset position"), accentColor, () -> {
					position.x = 8;
					position.y = 8;
				}));

		addRenderableWidget(new ScdButton(this.width / 2 + 4, this.height - 28, 120, 16,
				Component.literal("Done"), accentColor, () -> {
					config.save();
					Minecraft.getInstance().setScreen(parent);
				}));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		ScdTheme.scaledCenteredText(graphics, this.font, Component.literal("Drag the box below to reposition it"), this.width / 2, 12, ScdTheme.TEXT_PRIMARY);

		var drawn = renderer.render(graphics, this.font);
		bounds = drawn != null ? drawn : new ScdOverlayBox.Bounds(position.x, position.y, FALLBACK.width(), FALLBACK.height());
		if (drawn == null) {
			ScdTheme.label(graphics, this.font, "(no data yet to preview)", position.x + 4, position.y + 4, ScdTheme.TEXT_SECONDARY);
		}
		graphics.outline(bounds.x(), bounds.y(), bounds.width(), bounds.height(), accentColor);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0 && bounds != null && contains(bounds, event.x(), event.y())) {
			dragging = true;
			dragOffsetX = event.x() - position.x;
			dragOffsetY = event.y() - position.y;
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (dragging) {
			position.x = (int) Math.round(event.x() - dragOffsetX);
			position.y = (int) Math.round(event.y() - dragOffsetY);
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		dragging = false;
		return super.mouseReleased(event);
	}

	private static boolean contains(ScdOverlayBox.Bounds b, double x, double y) {
		return x >= b.x() && x < b.x() + b.width() && y >= b.y() && y < b.y() + b.height();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
