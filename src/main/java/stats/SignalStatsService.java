package stats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/**
 * Singleton service for signal tracking + periodic snapshots + export (JSON/CSV).
 * SQL/AutoTuner здесь не трогаем — только файловый экспорт и in-memory трекинг.
 */
public class SignalStatsService {

    private static final SignalStatsService INSTANCE = new SignalStatsService();
    public static SignalStatsService getInstance() { return INSTANCE; }

    // --- configurable ---
    private final int SNAPSHOT_INTERVAL_SECONDS = 120; // 2 минуты
    private final int SNAPSHOT_ROUNDS = 10;             // 4 снимка (~8 минут)
    private final boolean AUTO_EXPORT_ON_COMPLETE = true;
    private final Path EXPORT_DIR = Paths.get("./signal_exports");
    // ---------------------

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<String, SignalRecord> records = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");

    private SignalStatsService() {
        try { Files.createDirectories(EXPORT_DIR); } catch (IOException ignored) {}
    }

    // === ПУБЛИЧНЫЙ метод установки провайдера метрик ===
    public static void setMetricsProvider(ICurrentMetricsProvider provider) {
        CurrentMetricsProvider.set(provider);
    }

    // === Удобная обёртка: старт трека по готовому TradeSignal ===
    public String trackSignal(signal.TradeSignal ts, state.SymbolState s) {
        // Если в твоём TradeSignal поле называется иначе (например, funding вместо fundingRate),
        // замени вызов ниже на соответствующий геттер.
        double funding = 0.0;
        try {
            funding = ts.fundingRate();
        } catch (Throwable ignored) {
            // если у ts нет fundingRate(), оставим 0.0 или добавь другой геттер:
            // funding = ts.funding();
        }

        SignalSnapshot snap = new SignalSnapshot(
                System.currentTimeMillis(),
                ts.price(),
                ts.oiNow(),
                ts.volNow(),
                ts.buyRatio(),
                ts.voltRel(),
                funding,
                "initial",
                0.0,  // peakProfit на старте
                0.0   // drawdown   на старте
        );

        return trackSignal(
                ts.symbol(),
                ts.stage().name(),
                ts.direction(),
                ts.price(),
                ts.score(),
                ts.isMicro(),
                snap
        );
    }

    /** true, если все записи завершены */
    public boolean allCompleted() {
        return records.values().stream().allMatch(r -> r.completed);
    }

    // === Основной метод: создать запись и запланировать автоснимки ===
    public String trackSignal(String symbol,
                              String stage,
                              String direction,
                              double price,
                              double score,
                              boolean isMicro,
                              SignalSnapshot initialSnapshot) {
        long now = System.currentTimeMillis();
        String id = symbol + "_" + now;

        SignalRecord r = new SignalRecord(id, symbol, now, stage, direction, price, score, isMicro);
        if (initialSnapshot != null) r.addSnapshot(initialSnapshot);
        records.put(id, r);

        // планируем периодические снимки
        Runnable task = new SnapshotTask(id);
        ScheduledFuture<?> f = scheduler.scheduleAtFixedRate(
                task,
                SNAPSHOT_INTERVAL_SECONDS,
                SNAPSHOT_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
        scheduledTasks.put(id, f);

        // финализация после N раундов (+ небольшой запас один интервал)
        scheduler.schedule(() -> finishTracking(id),
                (long) SNAPSHOT_INTERVAL_SECONDS * SNAPSHOT_ROUNDS,
                TimeUnit.SECONDS);

        return id;
    }

    // добавить снимок вручную
    public boolean addManualSnapshot(String id, SignalSnapshot snap) {
        SignalRecord r = records.get(id);
        if (r == null) return false;
        r.addSnapshot(snap);
        return true;
    }

    // завершить и экспортировать (без SQL)
    public void finishTracking(String id) {
        SignalRecord r = records.get(id);
        if (r == null) return;
        if (r.completed) return; // защита от двойного завершения

        ScheduledFuture<?> f = scheduledTasks.remove(id);
        if (f != null) f.cancel(false);

        r.completed = true;

        if (AUTO_EXPORT_ON_COMPLETE) {
            try {
                exportRecord(r);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // экспорт одной записи в JSON + CSV
    public void exportRecord(SignalRecord r) throws IOException {
        String baseName = r.symbol + "_" + sdf.format(new Date(r.createdAtMs));
        Path json = EXPORT_DIR.resolve(baseName + ".json");
        Path csv  = EXPORT_DIR.resolve(baseName + ".csv");

        // JSON
        try (Writer w = Files.newBufferedWriter(json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            mapper.writeValue(w, r);
        }

        // CSV
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(csv, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            pw.println("id,symbol,createdAtMs,stage,direction,initPrice,initScore,isMicro");
            pw.printf(Locale.US,"%s,%s,%d,%s,%s,%.8f,%.4f,%b%n",
                    r.id, r.symbol, r.createdAtMs, r.stage, r.direction, r.initPrice, r.initScore, r.isMicro);
            pw.println();
            pw.println("timestamp,price,oi,volume,buyRatio,volatility,funding,peakProfit,drawdown,note");
            List<SignalSnapshot> copy;
            synchronized (r.snapshots) {
                copy = new ArrayList<>(r.snapshots);
            }
            for (SignalSnapshot s : copy) {
                pw.printf(Locale.US,
                        "%d,%.8f,%.2f,%.2f,%.4f,%.4f,%.8f,%.4f,%.4f,%s%n",
                        s.timestampMs, s.price, s.oiNow, s.volNow, s.buyRatio, s.voltRel, s.funding,
                        s.peakProfit, s.drawdown, sanitize(s.note));

            }

        }
    }

    private String sanitize(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\r\\n,]+", " ");
    }

    // плановый снимок
    private class SnapshotTask implements Runnable {
        private final String id;
        private int rounds = 0;

        SnapshotTask(String id) { this.id = id; }

        @Override
        public void run() {
            SignalRecord r = records.get(id);
            if (r == null) {
                ScheduledFuture<?> f = scheduledTasks.remove(id);
                if (f != null) f.cancel(false);
                return;
            }
            if (rounds >= SNAPSHOT_ROUNDS) {
                finishTracking(id);
                ScheduledFuture<?> f = scheduledTasks.remove(id);
                if (f != null) f.cancel(false);
                return;
            }

            ICurrentMetricsProvider prov = CurrentMetricsProvider.get();
            if (prov == null) { rounds++; return; }

            CurrentMetrics m = prov.getMetricsFor(r.symbol);
            if (m == null) { rounds++; return; }

            // Текущая доходность относительно входа
            double currPrice = m.price;
            double ret = (r.initPrice > 0.0) ? (currPrice / r.initPrice - 1.0) : 0.0;

            // Обновляем экстремумы
            r.maxReturn = Math.max(r.maxReturn, ret);
            r.minReturn = Math.min(r.minReturn, ret);

            SignalSnapshot snap = new SignalSnapshot(
                    System.currentTimeMillis(),
                    currPrice,
                    m.oiNow,
                    m.volNow,
                    m.buyRatio,
                    m.voltRel,
                    m.funding,
                    null,                        // note
                    r.maxReturn * 100.0,         // peak profit, %
                    r.minReturn * 100.0          // drawdown, %
            );

            r.addSnapshot(snap);
            rounds++;
        }
    }

    // helpers
    public int totalTracked() { return records.size(); }
    public List<SignalRecord> listAll() { return new ArrayList<>(records.values()); }

    // === metrics provider API ===
    public static class CurrentMetrics {
        public final double price;
        public final double oiNow;
        public final double volNow;
        public final double buyRatio;
        public final double voltRel;
        public final double funding;

        public CurrentMetrics(double price, double oiNow, double volNow, double buyRatio,
                              double voltRel, double funding) {
            this.price = price;
            this.oiNow = oiNow;
            this.volNow = volNow;
            this.buyRatio = buyRatio;
            this.voltRel = voltRel;
            this.funding = funding;
        }
    }

    public interface ICurrentMetricsProvider {
        CurrentMetrics getMetricsFor(String symbol);
    }

    // приватный holder + публичный сеттер выше
    private static final class CurrentMetricsProvider {
        private static ICurrentMetricsProvider provider;
        public static void set(ICurrentMetricsProvider p) { provider = p; }
        public static ICurrentMetricsProvider get() { return provider; }
    }
}
