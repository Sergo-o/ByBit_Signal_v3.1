package net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import store.MarketDataStore;

public class BybitRest {
    private static final OkHttpClient client = new OkHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    public static void preloadSymbols() {
        try {
            Request req = new Request.Builder()
                    .url("https://api.bybit.com/v5/market/instruments-info?category=linear")
                    .build();

            var resp = client.newCall(req).execute();
            JsonNode json = mapper.readTree(resp.body().string());

            for (JsonNode sym : json.get("result").get("list")) {
                String symbol = sym.get("symbol").asText();
                MarketDataStore.update(symbol, 0, 0, 0);
            }

            System.out.println("✅ Preloaded symbols: " + MarketDataStore.allSymbols().size());

        } catch (Exception e) {
            System.out.println("❌ Error preload symbols: " + e.getMessage());
        }
    }
}

