package com.scd.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * A movable HUD box showing the currently-hovered item's price, spread, and
 * a price-history sparkline. Only visible while hovering a bazaar item
 * (driven by ScdHoverState, shared with the tooltip) - not a persistent box.
 */
public class ScdGraphHud {
	private static final Identifier ID = Identifier.fromNamespaceAndPath("scd", "graph_overlay");
	private static final int BG_COLOR = 0x90000000;
	private static final int CHART_BG_COLOR = 0x40000000;
	private static final int SELL_LINE_COLOR = 0xFF55FF55;
	private static final int BUY_LINE_COLOR = 0xFFFFAA55;
	private static final int TITLE_COLOR = 0xFFFFAA00;
	private static final int LABEL_COLOR = 0xFFAAAAAA;
	private static final int PADDING = 4;
	private static final int WIDTH = 180;
	private static final int CHART_HEIGHT = 50;

	private final ScdConfig config;
	private final ScdPriceStore prices;
	private final ScdHistoryStore history;
	private final ScdHoverState hoverState;

	public ScdGraphHud(ScdConfig config, ScdPriceStore prices, ScdHistoryStore history, ScdHoverState hoverState) {
		this.config = config;
		this.prices = prices;
		this.history = history;
		this.hoverState = hoverState;
	}

	public void register() {
		HudElementRegistry.addLast(ID, (graphics, deltaTracker) -> ScdLog.guard("graph HUD", () -> {
			if (config.bazaar.graphEnabled) renderContent(graphics, Minecraft.getInstance().font);
		}));
	}

	/**
	 * Draws the box. `preview` bypasses the hover requirement (used by the
	 * "move HUD" edit screen, where nothing is actually being hovered) and
	 * shows a placeholder item instead.
	 */
	public ScdOverlayBox.Bounds renderContent(GuiGraphicsExtractor graphics, Font font) {
		return renderContent(graphics, font, false);
	}

	public ScdOverlayBox.Bounds renderContent(GuiGraphicsExtractor graphics, Font font, boolean preview) {
		String itemId = preview ? firstAvailableItemId() : hoverState.currentOrNull();
		if (itemId == null) return null;

		ScdApiClient.ProductPrice p = prices.get(itemId);
		if (p == null) return null;

		int x = config.bazaar.graphPosition.x + PADDING;
		int y = config.bazaar.graphPosition.y + PADDING;
		int lineHeight = font.lineHeight + 2;

		int textHeight = lineHeight * 4;
		int boxHeight = PADDING * 2 + textHeight + CHART_HEIGHT + PADDING;
		graphics.fill(x - PADDING, y - PADDING, x - PADDING + WIDTH, y - PADDING + boxHeight, BG_COLOR);

		graphics.text(font, Component.literal(p.name()), x, y, TITLE_COLOR, true);
		y += lineHeight;

		double spread = p.buyPrice() != 0 ? (p.buyPrice() - p.sellPrice()) / p.buyPrice() * 100 : 0;
		graphics.text(font, Component.literal("Sell " + ScdFormat.coins(p.sellPrice()) + "  Buy " + ScdFormat.coins(p.buyPrice())), x, y, LABEL_COLOR, true);
		y += lineHeight;
		graphics.text(font, Component.literal("Spread " + String.format("%.1f", spread) + "%"), x, y, LABEL_COLOR, true);
		y += lineHeight + 2;

		renderChart(graphics, font, x, y, WIDTH - PADDING * 2, CHART_HEIGHT, history.getOrFetch(itemId, config.bazaar.graphRange), p);

		return new ScdOverlayBox.Bounds(x - PADDING, config.bazaar.graphPosition.y, WIDTH, boxHeight);
	}

	// Just needs to be a real, near-always-priced item so the "move HUD" edit
	// screen has something to preview while nothing is actually being hovered.
	private static final String PREVIEW_ITEM_ID = "ENCHANTED_LAPIS_LAZULI";

	private String firstAvailableItemId() {
		return PREVIEW_ITEM_ID;
	}

	private void renderChart(GuiGraphicsExtractor g, Font font, int x, int y, int width, int height,
			List<ScdApiClient.HistoryPoint> rawPoints, ScdApiClient.ProductPrice current) {
		int labelHeight = font.lineHeight + 2;
		int chartY = y + labelHeight;

		if (rawPoints.size() < 2) {
			g.fill(x, chartY, x + width, chartY + height, CHART_BG_COLOR);
			g.text(font, Component.literal("Collecting history..."), x + 4, chartY + height / 2 - 4, LABEL_COLOR, true);
			return;
		}

		List<ScdApiClient.HistoryPoint> points = smooth(rawPoints, 3);

		long minT = points.get(0).timestampMs();
		long maxT = points.get(points.size() - 1).timestampMs();
		if (maxT <= minT) return;

		double minSell = current.sellPrice(), maxSell = current.sellPrice();
		double minBuy = current.buyPrice(), maxBuy = current.buyPrice();
		for (var p : points) {
			minSell = Math.min(minSell, p.sellPrice());
			maxSell = Math.max(maxSell, p.sellPrice());
			minBuy = Math.min(minBuy, p.buyPrice());
			maxBuy = Math.max(maxBuy, p.buyPrice());
		}
		double min = Math.min(minSell, minBuy);
		double max = Math.max(maxSell, maxBuy);
		if (max <= min) max = min + 1;

		int labelCount = 5;
		for (int i = 0; i < labelCount; i++) {
			double frac = (double) i / (labelCount - 1);
			String text = ScdFormat.coins(max - (max - min) * frac);
			int textWidth = font.width(text);
			int labelX = x + (int) Math.round(frac * (width - textWidth));
			g.text(font, Component.literal(text), labelX, y, LABEL_COLOR, true);
		}

		g.fill(x, chartY, x + width, chartY + height, CHART_BG_COLOR);
		plotLine(g, x, chartY, width, height, points, minT, maxT, min, max, true, SELL_LINE_COLOR);
		plotLine(g, x, chartY, width, height, points, minT, maxT, min, max, false, BUY_LINE_COLOR);

		// Mark where the *live* current price sits (not just the last, possibly
		// hour-stale, history bucket) at the chart's right edge, i.e. "now".
		double markerX = x + width - 1;
		drawMarker(g, markerX, chartY + height - 1 - (current.sellPrice() - min) / (max - min) * (height - 1), SELL_LINE_COLOR);
		drawMarker(g, markerX, chartY + height - 1 - (current.buyPrice() - min) / (max - min) * (height - 1), BUY_LINE_COLOR);
	}

	/** A small bordered dot marking exactly where the current price sits on the line. */
	private void drawMarker(GuiGraphicsExtractor g, double x, double y, int color) {
		int px = (int) Math.round(x);
		int py = (int) Math.round(y);
		g.fill(px - 2, py - 2, px + 3, py + 3, 0xFFFFFFFF);
		g.fill(px - 1, py - 1, px + 2, py + 2, color);
	}

	/** Simple centered moving average - trims hour-to-hour noise into a readable curve without hiding the trend. */
	private static List<ScdApiClient.HistoryPoint> smooth(List<ScdApiClient.HistoryPoint> points, int window) {
		if (points.size() < 3) return points;
		int half = window / 2;
		List<ScdApiClient.HistoryPoint> out = new ArrayList<>(points.size());
		for (int i = 0; i < points.size(); i++) {
			int lo = Math.max(0, i - half);
			int hi = Math.min(points.size() - 1, i + half);
			double sellSum = 0, buySum = 0;
			for (int j = lo; j <= hi; j++) {
				sellSum += points.get(j).sellPrice();
				buySum += points.get(j).buyPrice();
			}
			int count = hi - lo + 1;
			out.add(new ScdApiClient.HistoryPoint(points.get(i).timestampMs(), sellSum / count, buySum / count));
		}
		return out;
	}

	private double[] plotLine(GuiGraphicsExtractor g, int x, int y, int width, int height, List<ScdApiClient.HistoryPoint> points,
			long minT, long maxT, double min, double max, boolean useSell, int color) {
		double prevX = Double.NaN, prevY = Double.NaN;
		for (var p : points) {
			double value = useSell ? p.sellPrice() : p.buyPrice();
			double tFrac = (double) (p.timestampMs() - minT) / (maxT - minT);
			double vFrac = (value - min) / (max - min);
			double px = x + tFrac * (width - 1);
			double py = y + height - 1 - vFrac * (height - 1);
			if (!Double.isNaN(prevX)) {
				drawSegment(g, prevX, prevY, px, py, color);
			}
			prevX = px;
			prevY = py;
		}
		return new double[] { prevX, prevY };
	}

	/**
	 * A plain per-pixel stepped fill looks like a visible staircase on a
	 * diagonal, since GuiGraphicsExtractor has no actual line primitive -
	 * only solid rectangles. The core pixel on the line's path is always
	 * drawn fully opaque (so the line itself never dims/looks blended into
	 * the dark background); a neighboring pixel gets a soft partial-alpha
	 * fringe to ease the transition between steps instead of a hard edge.
	 */
	private void drawSegment(GuiGraphicsExtractor g, double x1, double y1, double x2, double y2, int color) {
		double dx = x2 - x1;
		double dy = y2 - y1;
		int steps = Math.max(1, (int) Math.round(Math.max(Math.abs(dx), Math.abs(dy))));
		boolean vertical = Math.abs(dy) >= Math.abs(dx);

		for (int i = 0; i <= steps; i++) {
			double t = (double) i / steps;
			double xf = x1 + dx * t;
			double yf = y1 + dy * t;
			if (vertical) {
				int py = (int) Math.round(yf);
				int xPrimary = (int) Math.round(xf);
				double fringe = Math.abs(xf - xPrimary);
				int xSecondary = xf >= xPrimary ? xPrimary + 1 : xPrimary - 1;
				blendPixel(g, xPrimary, py, color, 1.0);
				if (fringe > 0.05) blendPixel(g, xSecondary, py, color, fringe);
			} else {
				int px = (int) Math.round(xf);
				int yPrimary = (int) Math.round(yf);
				double fringe = Math.abs(yf - yPrimary);
				int ySecondary = yf >= yPrimary ? yPrimary + 1 : yPrimary - 1;
				blendPixel(g, px, yPrimary, color, 1.0);
				if (fringe > 0.05) blendPixel(g, px, ySecondary, color, fringe);
			}
		}
	}

	private void blendPixel(GuiGraphicsExtractor g, int x, int y, int color, double coverage) {
		int baseAlpha = (color >>> 24) & 0xFF;
		int alpha = (int) Math.round(baseAlpha * Math.max(0, Math.min(1, coverage)));
		if (alpha < 12) return;
		g.fill(x, y, x + 1, y + 1, (alpha << 24) | (color & 0x00FFFFFF));
	}
}
