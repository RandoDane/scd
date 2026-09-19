package com.scd.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Talks to the SCD backend (not Hypixel directly) - see server/src/routes/api.js. */
public class ScdApiClient {
	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();

	private String baseUrl;

	public ScdApiClient(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public record Icon(String material, Integer durability, String skinValue, String skinSignature) {
	}

	public record ProductPrice(String itemId, String name, double buyPrice, double sellPrice, Icon icon) {
	}

	/** Fetches every Bazaar product in one call - used to keep a local price cache for tooltips. */
	public CompletableFuture<List<ProductPrice>> fetchAll() {
		return getProducts(baseUrl + "/api/bazaar");
	}

	public CompletableFuture<List<ProductPrice>> search(String query) {
		String url = baseUrl + "/api/bazaar/search?q=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
		return getProducts(url);
	}

	private CompletableFuture<List<ProductPrice>> getProducts(String url) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();

		return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenApply(res -> {
					if (res.statusCode() != 200) {
						throw new RuntimeException("HTTP " + res.statusCode());
					}
					JsonArray products = JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonArray("products");
					List<ProductPrice> out = new ArrayList<>();
					for (var el : products) {
						JsonObject obj = el.getAsJsonObject();
						out.add(new ProductPrice(
								obj.get("item_id").getAsString(),
								obj.get("name").getAsString(),
								obj.get("buy_price").getAsDouble(),
								obj.get("sell_price").getAsDouble(),
								parseIcon(obj.getAsJsonObject("icon"))));
					}
					return out;
				});
	}

	/**
	 * Attribute NBT key ("undead_resistance") -> Bazaar product id ("SHARD_TANK_ZOMBIE").
	 * An attribute shard's own NBT never says which shard it is - only the attribute it
	 * grants - and the two names are frequently unrelated, so this table (scraped
	 * server-side from the wiki) is the only way to resolve one to the other.
	 */
	public CompletableFuture<Map<String, String>> fetchAttributeShardMap() {
		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/attribute-shards"))
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();

		return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenApply(res -> {
					if (res.statusCode() != 200) {
						throw new RuntimeException("HTTP " + res.statusCode());
					}
					JsonObject obj = JsonParser.parseString(res.body()).getAsJsonObject();
					Map<String, String> out = new HashMap<>();
					for (var entry : obj.entrySet()) {
						out.put(entry.getKey(), entry.getValue().getAsString());
					}
					return out;
				});
	}

	public record MayorPerk(String name, String description) {
	}

	/** Currently-active perks: the elected mayor's own full perk set, plus the minister's single granted perk if one is serving. */
	public record MayorInfo(String mayorName, List<MayorPerk> perks) {
	}

	/** GETs /api/mayor - see server/src/routes/api.js, which itself is fed by polling Hypixel's own public election resource. */
	public CompletableFuture<MayorInfo> fetchMayor() {
		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/mayor"))
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();

		return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenApply(res -> {
					if (res.statusCode() != 200) {
						throw new RuntimeException("HTTP " + res.statusCode());
					}
					JsonObject body = JsonParser.parseString(res.body()).getAsJsonObject();
					JsonObject mayor = body.has("mayor") && body.get("mayor").isJsonObject() ? body.getAsJsonObject("mayor") : null;
					if (mayor == null) return new MayorInfo(null, List.of());

					List<MayorPerk> perks = new ArrayList<>();
					if (mayor.has("perks") && mayor.get("perks").isJsonArray()) {
						for (var el : mayor.getAsJsonArray("perks")) {
							JsonObject p = el.getAsJsonObject();
							perks.add(new MayorPerk(nullableString(p, "name"), nullableString(p, "description")));
						}
					}
					if (mayor.has("minister_perk") && mayor.get("minister_perk").isJsonObject()) {
						JsonObject p = mayor.getAsJsonObject("minister_perk");
						perks.add(new MayorPerk(nullableString(p, "name"), nullableString(p, "description")));
					}
					return new MayorInfo(nullableString(mayor, "mayor_name"), perks);
				});
	}

	public record HistoryPoint(long timestampMs, double sellPrice, double buyPrice) {
	}

	public CompletableFuture<List<HistoryPoint>> fetchHistory(String itemId, String range) {
		String url = baseUrl + "/api/bazaar/" + itemId + "/history?range=" + range;
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();

		return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenApply(res -> {
					if (res.statusCode() != 200) {
						throw new RuntimeException("HTTP " + res.statusCode());
					}
					JsonObject body = JsonParser.parseString(res.body()).getAsJsonObject();
					boolean raw = "raw".equals(body.get("granularity").getAsString());
					JsonArray points = body.getAsJsonArray("points");

					List<HistoryPoint> out = new ArrayList<>();
					for (var el : points) {
						JsonObject obj = el.getAsJsonObject();
						if (raw) {
							out.add(new HistoryPoint(obj.get("ts").getAsLong(), obj.get("sell_price").getAsDouble(), obj.get("buy_price").getAsDouble()));
						} else {
							long ts = java.time.LocalDate.parse(obj.get("day").getAsString())
									.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
							out.add(new HistoryPoint(ts, obj.get("avg_sell").getAsDouble(), obj.get("avg_buy").getAsDouble()));
						}
					}
					return out;
				});
	}

	private static Icon parseIcon(JsonObject icon) {
		if (icon == null) return null;
		JsonObject skin = icon.has("skin") && icon.get("skin").isJsonObject() ? icon.getAsJsonObject("skin") : null;
		return new Icon(
				nullableString(icon, "material"),
				icon.has("durability") && !icon.get("durability").isJsonNull() ? icon.get("durability").getAsInt() : null,
				skin != null ? nullableString(skin, "value") : null,
				skin != null ? nullableString(skin, "signature") : null);
	}

	private static String nullableString(JsonObject obj, String key) {
		return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
	}
}
