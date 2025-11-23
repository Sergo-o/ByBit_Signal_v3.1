package net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import app.Settings;
import store.MarketDataStore;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;

public class OiRestUpdater {

    private static final OkHttpClient http = new OkHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String URL =
            "https://api.bybit.com/v5/market/tickers?category=linear";

    private static final long BASE_INTERVAL_MS = 30_000L;      // нормальная частота
    private static final long MAX_INTERVAL_MS  = 5 * 60_000L;  // потолок паузы
    private static final int  MAX_RETRIES_PER_CYCLE = 2;       // попыток внутри updateOnce

    // 🔴 НОВОЕ: максимум подряд неудачных циклов, после которых глушим апдейтер
    private static final int  MAX_FAILS = 20;

    private static int lastOiUpdateCount = -1;

    public static void start() {
        Thread t = new Thread(() -> {
            long currentInterval = BASE_INTERVAL_MS;
            int consecutiveFailures = 0;

            while (Settings.RUNNING) {
                long cycleStart = System.currentTimeMillis();
                boolean success = updateOnce();

                if (success) {
                    // Успех → сбрасываем backoff
                    consecutiveFailures = 0;
                    currentInterval = BASE_INTERVAL_MS;
                } else {
                    // Ошибка → считаем подряд и при необходимости отключаем апдейтер
                    consecutiveFailures++;

                    if (consecutiveFailures > MAX_FAILS) {
                        System.err.println("[OI] too many failures in a row (" + consecutiveFailures +
                                "), OI updater disabled until restart");
                        break; // выходим из while → поток завершится
                    }

                    long factor = Math.min(consecutiveFailures, 5);
                    long backoff = BASE_INTERVAL_MS * (1L << factor);
                    currentInterval = Math.min(backoff, MAX_INTERVAL_MS);

                    System.err.println("[OI] fail #" + consecutiveFailures +
                            ", next in " + currentInterval + " ms");
                }

                long elapsed = System.currentTimeMillis() - cycleStart;
                long sleepMs = currentInterval - elapsed;
                if (sleepMs < 1_000L) sleepMs = 1_000L;

                // небольшой джиттер
                sleepMs += ThreadLocalRandom.current().nextLong(0, 1_000L);

                try { Thread.sleep(sleepMs); }
                catch (InterruptedException ignored) {}
            }

            System.out.println("[OI] updater stopped (RUNNING=false or MAX_FAILS reached)");
        }, "oi-rest-updater");

        t.setDaemon(true);
        t.start();
    }


    /**
     * Один цикл обновления.
     * Возвращает true — если OI успешно обновлён.
     */
    private static boolean updateOnce() {
        for (int attempt = 1; attempt <= MAX_RETRIES_PER_CYCLE; attempt++) {
            if (!Settings.RUNNING) return false;

            try {
                Request req = new Request.Builder().url(URL).build();

                try (Response res = http.newCall(req).execute()) {
                    int code = res.code();

                    // 429 — уважение rate-limit
                    if (code == 429) {
                        long wait = parseRetryAfter(res);
                        System.err.println("[OI] HTTP 429 — pausing for " + wait + "ms");
                        Thread.sleep(wait);
                        return false; // выходим наружу, пусть большой backoff подхватит
                    }

                    // 5xx — проблемы у Bybit → не долбим
                    if (code >= 500) {
                        System.err.println("[OI] server error " + code);
                        return false;
                    }

                    // прочие ошибки — не ретраим
                    if (!res.isSuccessful() || res.body() == null) {
                        System.err.println("[OI] HTTP error " + code);
                        return false;
                    }

                    // === твой исходный парсинг OI ===
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

                    return true;
                }

            } catch (IOException e) {
                System.err.println("[OI] IO error (attempt " + attempt + "): " + e.getMessage());
                // короткий локальный backoff
                if (attempt < MAX_RETRIES_PER_CYCLE) {
                    try { Thread.sleep(1_000L * attempt); } catch (InterruptedException ignored) {}
                }
            } catch (InterruptedException ie) {
                System.err.println("[OI] interrupted");
                return false;
            } catch (Exception e) {
                System.err.println("[OI] unexpected: " + e.getMessage());
                return false;
            }
        }
        return false;
    }

    private static long parseRetryAfter(Response res) {
        String h = res.header("Retry-After");
        if (h == null) return 60_000L;
        try {
            long sec = Long.parseLong(h.trim());
            return Math.max(5_000L, sec * 1000L);
        } catch (NumberFormatException e) {
            return 60_000L;
        }
    }
}


