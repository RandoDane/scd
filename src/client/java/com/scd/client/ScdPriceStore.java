package com.scd.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Local mirror of every Bazaar product's latest price, refreshed on an interval by ScdClient. */
public class ScdPriceStore {
	private final Map<String, ScdApiClient.ProductPrice> byId = new ConcurrentHashMap<>();

	public void replaceAll(java.util.List<ScdApiClient.ProductPrice> products) {
		byId.clear();
		for (var p : products) {
			byId.put(p.itemId(), p);
		}
	}

	public ScdApiClient.ProductPrice get(String itemId) {
		return byId.get(itemId);
	}

	public int size() {
		return byId.size();
	}
}
