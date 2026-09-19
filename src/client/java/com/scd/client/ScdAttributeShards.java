package com.scd.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Local cache of the attribute-NBT-key -> Bazaar-shard-id table fetched from ScdApiClient.fetchAttributeShardMap(). */
public class ScdAttributeShards {
	private final Map<String, String> byAttribute = new ConcurrentHashMap<>();

	public void replaceAll(Map<String, String> map) {
		byAttribute.clear();
		byAttribute.putAll(map);
	}

	public String shardIdFor(String attributeKey) {
		return byAttribute.get(attributeKey);
	}
}
