package net;

import app.Settings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import core.PumpLiquidityAnalyzer;
import model.CoinInfo;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import store.MarketDataStore;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class BybitWsClient {
    private static int liqFailCount = 0;
    private static long liqReconnectDelayMs = 5_000L;
    private static final long LIQ_RECONNECT_DELAY_MAX_MS = 60_000L;
    private static final int  LIQ_FAIL_MAX = 20; // после 20 подряд падений просто отключаемся

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final OkHttpClient client = new OkHttpClient();

    private static WebSocket klineWS;
    private static WebSocket tradeWS;
    private static WebSocket liquidationWS;
    private static WebSocket tickerWS;

    private static PumpLiquidityAnalyzer analyzerRef;

    private static final String WS_URL = "wss://stream.bybit.com/v5/public/linear";
    private static long lastHeartbeat = 0;

    private static final java.util.concurrent.ScheduledExecutorService RECONNECT_EXEC =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ws-reconnect");
                t.setDaemon(true);
                return t;
            });

    private static void scheduleReconnectKlines(PumpLiquidityAnalyzer analyzer) {
        RECONNECT_EXEC.schedule(
                () -> startKlines(analyzer),
                5,
                java.util.concurrent.TimeUnit.SECONDS
        );
    }

    private static WebSocket connect(WebSocketListener listener) {
        Request req = new Request.Builder().url(WS_URL).build();
        return client.newWebSocket(req, listener);
    }

    private static void subscribe(WebSocket ws, List<String> topics) {
        var args = mapper.createArrayNode();
        for (String t : topics) args.add(t);
        var root = mapper.createObjectNode();
        root.put("op", "subscribe");
        root.set("args", args);
        ws.send(root.toString());
    }

    public static void startKlines(PumpLiquidityAnalyzer analyzer) {
        analyzerRef = analyzer;
        klineWS = connect(new WebSocketListener() {
            @Override public void onOpen(@NotNull WebSocket ws, @NotNull Response r) {
                List<String> topics = MarketDataStore.allSymbols().stream().map(s -> "kline.1." + s).toList();
                for (int i = 0; i < topics.size(); i += 200) {
                    subscribe(ws, topics.subList(i, Math.min(i + 200, topics.size())));
                }
                System.out.println("✅ WS klines subscribed (" + topics.size() + ")");
            }

            @Override public void onMessage(@NotNull WebSocket webSocket, @NotNull String msg) {
                try {
                    JsonNode root = mapper.readTree(msg);
                    if (!root.has("topic") || !root.get("topic").asText().startsWith("kline")) return;

                    JsonNode bar = root.path("data").get(0);
                    if (bar == null) return;

                    boolean closed = bar.path("confirm").asBoolean(false);
                    if (!closed) return;

                    String symbol = root.get("topic").asText().split("\\.")[2];

                    double close = bar.path("close").asDouble(0.0);
                    double volumeUsd = bar.path("turnover").asDouble(0.0);

                    CoinInfo info = MarketDataStore.get(symbol);
                    if (info == null) return;

                    analyzer.onKline(symbol, close, volumeUsd, info.openInterest, info.fundingRate);

                } catch (Exception ignore) {}
            }
            public void onFailure(@NotNull WebSocket ws, @NotNull Throwable t, Response r) {
                System.err.println("[WS Klines Failure] " + t.getMessage());
                klineWS = null;
                scheduleReconnectKlines("failure");
            }

            @Override
            public void onClosed(@NotNull WebSocket ws, int code, @NotNull String reason) {
                System.err.println("[WS Klines Closed] " + code + " " + reason);
                klineWS = null;
                scheduleReconnectKlines("closed");
            }
        });
    }

    public static void startTrades(PumpLiquidityAnalyzer analyzer) {
        analyzerRef = analyzer;
        tradeWS = connect(new WebSocketListener() {
            @Override
            public void onOpen(@NotNull WebSocket ws, @NotNull Response resp) {
                List<String> topics = MarketDataStore.allSymbols()
                        .stream()
                        .map(s -> "publicTrade." + s)
                        .toList();

                for (int i = 0; i < topics.size(); i += 200) {
                    subscribe(ws, topics.subList(i, Math.min(i + 200, topics.size())));
                }

                System.out.println("✅ WS trades subscribed (" + topics.size() + ")");
            }

            @Override
            public void onMessage(@NotNull WebSocket ws, @NotNull String text) {
                try {
                    JsonNode json = mapper.readTree(text);

                    if (!json.has("topic") || !json.get("topic").asText().startsWith("publicTrade")) return;
                    JsonNode data = json.path("data");
                    if (!data.isArray()) return;

                    for (JsonNode t : data) {
                        String symbol = t.path("s").asText("");
                        String side   = t.path("S").asText("");
                        double price  = t.path("p").asDouble(0);
                        double size   = t.path("v").asDouble(0);
                        if (symbol.isEmpty() || price <= 0 || size <= 0) continue;

                        double usd = price * size;
                        analyzer.onTrade(symbol, "Buy".equals(side), usd);
                    }

                } catch (Exception ignore) {}
            }
            @Override
            public void onFailure(@NotNull WebSocket ws, @NotNull Throwable t, Response r) {
                System.err.println("[WS Trades Failure] " + t.getMessage());
                tradeWS = null;
                scheduleReconnectTrades("failure");
            }

            @Override
            public void onClosed(@NotNull WebSocket ws, int code, @NotNull String reason) {
                System.err.println("[WS Trades Closed] " + code + " " + reason);
                tradeWS = null;
                scheduleReconnectTrades("closed");
            }
        });
    }



    public static void startLiquidations(PumpLiquidityAnalyzer analyzer) {
        analyzerRef = analyzer;
        liquidationWS = connect(new WebSocketListener() {
            @Override public void onOpen(@NotNull WebSocket ws, @NotNull Response r) {
                List<String> topics = MarketDataStore.allSymbols().stream().map(s -> "liquidation." + s).toList();
                for (int i = 0; i < topics.size(); i += 200) {
                    subscribe(ws, topics.subList(i, Math.min(i + 200, topics.size())));
                }
                System.out.println("✅ WS liquidations subscribed (" + topics.size() + ")");
                liqFailCount = 0;
                liqReconnectDelayMs = 5_000L;
            }

            @Override
            public void onMessage(@NotNull WebSocket ws, @NotNull String text) {
                try {
                    JsonNode json = mapper.readTree(text);
                    if (!json.has("topic") || !json.get("topic").asText().startsWith("liquidation")) return;

                    JsonNode d = json.path("data");
                    String symbol = d.path("symbol").asText("");
                    String side   = d.path("side").asText("");
                    double price  = d.path("price").asDouble(0.0);
                    double size   = d.path("size").asDouble(0.0);

                    // Проверяем корректность
                    if (symbol.isEmpty() || price <= 0 || size <= 0 || side.isEmpty()) return;

                    // Определяем направление ликвидации
                    // side=Sell → ликвидируются шорты (лонговая сторона выигрывает)
                    boolean longSideWasLiquidated = side.equalsIgnoreCase("Sell");

                    // USD-эквивалент ликвидации
                    double usd = price * size;

                    // Передаём в анализатор
                    analyzer.onLiquidation(symbol, longSideWasLiquidated, usd);

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onFailure(@NotNull WebSocket ws, @NotNull Throwable t, Response r) {
                String reason = (t.getMessage() != null ? t.getMessage() : "<no message>");
                String type   = t.getClass().getSimpleName();
                String http   = (r != null ? (r.code() + " " + r.message()) : "no HTTP response");

                System.err.println("[WS Liqs Failure] " + type + " | " + http + " | " + reason);
                t.printStackTrace(); // можно временно оставить, потом выключить

                liquidationWS = null;
                scheduleReconnectLiquidations("failure");
            }

            @Override
            public void onClosed(@NotNull WebSocket ws, int code, @NotNull String reason) {
                System.err.println("[WS Liqs Closed] " + code + " " + reason);
                liquidationWS = null;
                scheduleReconnectLiquidations("closed");
            }
        });
    }

    public static void startTickers() {
        tickerWS = connect(new WebSocketListener() {
            @Override public void onOpen(@NotNull WebSocket ws, @NotNull Response r) {
                List<String> topics = MarketDataStore.allSymbols().stream().map(s -> "tickers." + s).toList();
                for (int i = 0; i < topics.size(); i += 200) {
                    subscribe(ws, topics.subList(i, Math.min(i + 200, topics.size())));
                }
                System.out.println("✅ WS tickers subscribed (" + topics.size() + ")");
            }

            @Override public void onMessage(@NotNull WebSocket ws, @NotNull String text) {
                try {
                    JsonNode json = mapper.readTree(text);
                    String topic = json.path("topic").asText("");
                    if (!topic.startsWith("tickers")) return;

                    JsonNode d = json.path("data");
                    String symbol = d.path("symbol").asText("");
                    if (symbol.isEmpty()) return;

                    double price = d.path("lastPrice").asDouble(0);
                    double oi = d.path("openInterestValue").asDouble(0);
                    double funding = d.path("fundingRate").asDouble(0);

                    var info = MarketDataStore.get(symbol);
                    if (info == null) return;

                    if (price > 0) info.lastPrice = price;
                    if (oi > 0) info.openInterest = oi;
                    if (funding != 0) info.fundingRate = funding;

                    if (symbol.equals("BTCUSDT")) {
                        long now = System.currentTimeMillis();
                        if (now - lastHeartbeat > 120_000) {
                            System.out.printf("[WS OK] BTC %.2f OI=%.0f%n", info.lastPrice, info.openInterest);
                            lastHeartbeat = now;
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[WS Tickers Error] " + e.getMessage());
                }
            }
            @Override
            public void onFailure(@NotNull WebSocket ws, @NotNull Throwable t, Response r) {
                System.err.println("[WS Tickers Failure] " + t.getMessage());
                tickerWS = null;
                scheduleReconnectTickers("failure");
            }

            @Override
            public void onClosed(@NotNull WebSocket ws, int code, @NotNull String reason) {
                System.err.println("[WS Tickers Closed] " + code + " " + reason);
                tickerWS = null;
                scheduleReconnectTickers("closed");
            }
        });
    }

    private static void scheduleReconnectKlines(String reason) {
        if (!app.Settings.RUNNING) return;
        if (analyzerRef == null) return;
        System.err.println("[WS Klines] reconnect in 5s (" + reason + ")");
        RECONNECT_EXEC.schedule(() -> {
            try {
                startKlines(analyzerRef);
            } catch (Exception e) {
                System.err.println("[WS Klines] reconnect error: " + e.getMessage());
            }
        }, 5, TimeUnit.SECONDS);
    }

    private static void scheduleReconnectTrades(String reason) {
        if (!app.Settings.RUNNING) return;
        if (analyzerRef == null) return;
        System.err.println("[WS Trades] reconnect in 5s (" + reason + ")");
        RECONNECT_EXEC.schedule(() -> {
            try {
                startTrades(analyzerRef);
            } catch (Exception e) {
                System.err.println("[WS Trades] reconnect error: " + e.getMessage());
            }
        }, 5, TimeUnit.SECONDS);
    }

    private static void scheduleReconnectLiquidations(String reason) {
        if (!app.Settings.RUNNING) return;

        liqFailCount++;
        if (liqFailCount > LIQ_FAIL_MAX) {
            System.err.println("[WS Liqs] disabled after " + liqFailCount + " failures");
            return;
        }

        long delay = liqReconnectDelayMs;
        liqReconnectDelayMs = Math.min(liqReconnectDelayMs * 2, LIQ_RECONNECT_DELAY_MAX_MS);

        System.err.println("[WS Liqs] reconnect in " + delay + " ms (" + reason + "), fail# " + liqFailCount);

        RECONNECT_EXEC.schedule(() -> {
            try {
                startLiquidations(analyzerRef);
            } catch (Exception e) {
                System.err.println("[WS Liqs] reconnect error: " + e.getMessage());
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private static void scheduleReconnectTickers(String reason) {
        if (!app.Settings.RUNNING) return;
        System.err.println("[WS Tickers] reconnect in 5s (" + reason + ")");
        RECONNECT_EXEC.schedule(() -> {
            try {
                startTickers();
            } catch (Exception e) {
                System.err.println("[WS Tickers] reconnect error: " + e.getMessage());
            }
        }, 5, TimeUnit.SECONDS);
    }

    public static void shutdown() {
        Settings.RUNNING = false; // на всякий, продублируем

        try {
            if (klineWS != null) {
                klineWS.close(1000, "shutdown");
            }
        } catch (Exception ignored) {}

        try {
            if (tradeWS != null) {
                tradeWS.close(1000, "shutdown");
            }
        } catch (Exception ignored) {}

        try {
            if (liquidationWS != null) {
                liquidationWS.close(1000, "shutdown");
            }
        } catch (Exception ignored) {}

        try {
            if (tickerWS != null) {
                tickerWS.close(1000, "shutdown");
            }
        } catch (Exception ignored) {}

        RECONNECT_EXEC.shutdownNow();
        System.out.println("[WS] shutdown requested");
    }


}
