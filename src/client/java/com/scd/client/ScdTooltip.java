package com.scd.client;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.network.chat.Component;

public class ScdTooltip {
	private static final int HEADER_COLOR = 0xFF808080;
	private static final int SELL_COLOR = 0xFF55FF55;
	private static final int BUY_COLOR = 0xFFFFAA55;
	private static final int SPREAD_COLOR = 0xFFAAAAAA;

	private final ScdConfig config;
	private final ScdPriceStore prices;
	private final ScdHoverState hoverState;
	private final ScdAttributeShards attributeShards;

	public ScdTooltip(ScdConfig config, ScdPriceStore prices, ScdHoverState hoverState, ScdAttributeShards attributeShards) {
		this.config = config;
		this.prices = prices;
		this.hoverState = hoverState;
		this.attributeShards = attributeShards;
	}

	public void register() {
		ItemTooltipCallback.EVENT.register((stack, context, flag, lines) ->
				ScdLog.guard("tooltip", () -> onTooltip(stack, lines)));
	}

	private void onTooltip(net.minecraft.world.item.ItemStack stack, java.util.List<Component> lines) {
		String id = SkyblockItems.getId(stack, attributeShards);
		// Recorded before the null-id check below, so /scd item nbt still has something to show for an
		// item whose display stack carries NBT in a shape SkyblockItems.getId doesn't recognize.
		hoverState.recordHover(id, SkyblockItems.getRawCustomDataOrNull(stack));
		if (id == null) return;

		if (!config.bazaar.tooltipEnabled) return;

		ScdApiClient.ProductPrice price = prices.get(id);
		if (price == null) return;

		double spread = price.buyPrice() != 0 ? (price.buyPrice() - price.sellPrice()) / price.buyPrice() * 100 : 0;

		lines.add(Component.literal("Bazaar").withStyle(s -> s.withColor(HEADER_COLOR)));
		lines.add(Component.literal("  Insta-Sell: ").withStyle(s -> s.withColor(HEADER_COLOR))
				.append(Component.literal(ScdFormat.coins(price.sellPrice()) + " coins").withStyle(s -> s.withColor(SELL_COLOR))));
		lines.add(Component.literal("  Insta-Buy:  ").withStyle(s -> s.withColor(HEADER_COLOR))
				.append(Component.literal(ScdFormat.coins(price.buyPrice()) + " coins").withStyle(s -> s.withColor(BUY_COLOR))));
		lines.add(Component.literal("  Spread: ").withStyle(s -> s.withColor(HEADER_COLOR))
				.append(Component.literal(String.format("%.1f%%", spread)).withStyle(s -> s.withColor(SPREAD_COLOR))));
	}
}
