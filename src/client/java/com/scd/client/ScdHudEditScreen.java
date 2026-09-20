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
	// Half-width of the little square grabbed to resize, and the clamp range for Pos.scale.
	private static final int HANDLE_RADIUS = 3;
	// Package-visible - ScdHudAppearanceScreen's box-size slider drives the same Pos.scale field via
	// a different control, and shares this range so both ways of resizing a HUD agree on the limits.
	static final float MIN_SCALE = 0.5f;
	static final float MAX_SCALE = 2.5f;

	private final Screen parent;
	private final ScdConfig config;
	private final ScdConfig.Pos position;
	private final PreviewRenderer renderer;
	private final int accentColor;

	private ScdOverlayBox.Bounds bounds;
	private boolean dragging;
	private double dragOffsetX;
	private double dragOffsetY;
	// Resizing pins the box's top-left corner (position.x/y) in place and scales everything from
	// there, driven by how far the bottom-right corner handle has moved relative to where the drag
	// started - see mouseDragged.
	private boolean resizing;
	private float resizeStartScale;
	private double resizeStartCornerDist;

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
				Component.literal("Reset position && size"), accentColor, () -> {
					position.x = 8;
					position.y = 8;
					position.scale = 1.0f;
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

		ScdTheme.scaledCenteredText(graphics, this.font, Component.literal("Drag the box to reposition it, or the corner handle to resize it"), this.width / 2, 12, ScdTheme.TEXT_PRIMARY);

		var drawn = renderer.render(graphics, this.font);
		bounds = drawn != null ? drawn : new ScdOverlayBox.Bounds(position.x, position.y, FALLBACK.width(), FALLBACK.height());
		if (drawn == null) {
			ScdTheme.label(graphics, this.font, "(no data yet to preview)", position.x + 4, position.y + 4, ScdTheme.TEXT_SECONDARY);
		}
		// No standalone selection outline here anymore - drawing a sharp rectangle on top of a
		// rounded-panel box (see ScdTheme.panelRounded) looked exactly wrong, poking out past the
		// actual rounded corners. The panel's own texture already has a visible border baked in, and
		// the resize handle below is enough affordance for "this is the draggable box."

		int hx = bounds.x() + bounds.width();
		int hy = bounds.y() + bounds.height();
		graphics.fill(hx - HANDLE_RADIUS, hy - HANDLE_RADIUS, hx + HANDLE_RADIUS, hy + HANDLE_RADIUS, accentColor);
		graphics.outline(hx - HANDLE_RADIUS, hy - HANDLE_RADIUS, HANDLE_RADIUS * 2, HANDLE_RADIUS * 2, ScdTheme.TEXT_PRIMARY);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0 && bounds != null) {
			if (inResizeHandle(bounds, event.x(), event.y())) {
				resizing = true;
				// A corrupted/never-initialized scale of 0 (e.g. an old config saved before this field
				// existed) would otherwise be permanently unrecoverable here: newScale is this value times
				// a ratio in mouseDragged, and 0 times anything is still 0. Treating anything below the
				// clamp floor as "not actually set yet" and starting the drag from the standard size
				// instead lets a stuck-at-0 box be fixed by dragging, not just by the Reset button.
				resizeStartScale = position.scale >= MIN_SCALE ? position.scale : 1.0f;
				resizeStartCornerDist = distanceFromAnchor(event.x(), event.y());
				return true;
			}
			if (contains(bounds, event.x(), event.y())) {
				dragging = true;
				dragOffsetX = event.x() - position.x;
				dragOffsetY = event.y() - position.y;
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (resizing) {
			// Growing/shrinking is driven by the ratio of "how far the corner is now" vs "how far it
			// was when the drag started," relative to the pinned top-left anchor - not the corner's
			// raw position - so grabbing anywhere near the handle doesn't jump-scale on the first pixel.
			double currentDist = distanceFromAnchor(event.x(), event.y());
			if (resizeStartCornerDist > 1) {
				float newScale = (float) (resizeStartScale * (currentDist / resizeStartCornerDist));
				position.scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, newScale));
			}
			return true;
		}
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
		resizing = false;
		return super.mouseReleased(event);
	}

	private double distanceFromAnchor(double x, double y) {
		double dx = x - position.x;
		double dy = y - position.y;
		return Math.sqrt(dx * dx + dy * dy);
	}

	private static boolean inResizeHandle(ScdOverlayBox.Bounds b, double x, double y) {
		int hx = b.x() + b.width();
		int hy = b.y() + b.height();
		return x >= hx - HANDLE_RADIUS * 2 && x <= hx + HANDLE_RADIUS * 2 && y >= hy - HANDLE_RADIUS * 2 && y <= hy + HANDLE_RADIUS * 2;
	}

	private static boolean contains(ScdOverlayBox.Bounds b, double x, double y) {
		return x >= b.x() && x < b.x() + b.width() && y >= b.y() && y < b.y() + b.height();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
