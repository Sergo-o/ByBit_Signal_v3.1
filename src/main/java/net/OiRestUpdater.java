package net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import store.MarketDataStore;

public class OiRestUpdater {
    private static final OkHttpClient http = new OkHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String URL = "https://api.bybit.com/v5/market/tickers?category=linear";
    private static int lastOiUpdateCount = -1;

    public static void start() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Request req = new Request.Builder().url(URL).build();
                    try (Response res = http.newCall(req).execute()) {
                        if (!res.isSuccessful() || res.body() == null) {
                            System.err.println("[OI] HTTP error: " + (res.code()));
                        } else {
                            JsonNode root = mapper.readTree(res.body().string());
                            JsonNode list = root.path("result").path("list");
                            if (list.isArray()) {
                                int updated = 0;
                                for (JsonNode n : list) {
                                    String symbol = n.path("symbol").asText("");
                                    double oi = n.path("openInterestValue").asDouble(0.0);
                                    if (!symbol.isEmpty()) {
                                        MarketDataStore.updateOI(symbol, oi);
                                        updated++;
                                    }
                                }
                                if (lastOiUpdateCount != updated) {
                                    System.out.println("🔄 OI refresh: " + updated + " symbols");
                                    lastOiUpdateCount = updated;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[OI] REST error: " + e.getMessage());
                }
                try { Thread.sleep(30_000); } catch (InterruptedException ignored) {}
            }
        }, "oi-rest-updater");
        t.setDaemon(true);
        t.start();
    }
}

