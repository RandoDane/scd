package com.scd.client;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small per-item cache of price history. The graph HUD asks for history every
 * frame while hovering an item, so this only ever fetches once per item
 * (until evicted) instead of hammering the backend on every render call.
 */
public class ScdHistoryStore {
	private static final int MAX_CACHED_ITEMS = 20;

	private final ScdApiClient api;
	private final Map<String, List<ScdApiClient.HistoryPoint>> cache = new ConcurrentHashMap<>();
	private final Set<String> loading = ConcurrentHashMap.newKeySet();

	public ScdHistoryStore(ScdApiClient api) {
		this.api = api;
	}

	/** Returns cached points immediately (possibly empty if not loaded yet) and kicks off a fetch if needed. */
	public List<ScdApiClient.HistoryPoint> getOrFetch(String itemId, String range) {
		List<ScdApiClient.HistoryPoint> cached = cache.get(itemId);
		if (cached != null) return cached;

		if (loading.add(itemId)) {
			if (cache.size() > MAX_CACHED_ITEMS) cache.clear();
			api.fetchHistory(itemId, range)
					.thenAccept(points -> cache.put(itemId, points))
					.exceptionally(err -> {
						ScdLog.warn("Failed to fetch history for " + itemId, err);
						return null;
					})
					.whenComplete((v, err) -> loading.remove(itemId));
		}
		return List.of();
	}
}
