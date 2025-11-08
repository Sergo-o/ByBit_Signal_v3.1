package stats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import tuning.AutoTuner;

import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/**
 * Singleton service for signal tracking + periodic snapshots + export (JSON/CSV).
 */
public class SignalStatsService {

    private static final SignalStatsService INSTANCE = new SignalStatsService();
    public static SignalStatsService getInstance() { return INSTANCE; }

    // --- configurable ---
    private final int  SNAPSHOT_INTERVAL_SECONDS = 120; // 2 минуты
    private final int  SNAPSHOT_ROUNDS = 4;             // 4 раунда (~8 минут)
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

    // === публичный метод для установки провайдера метрик ===
    public static void setMetricsProvider(ICurrentMetricsProvider provider) {
        CurrentMetricsProvider.set(provider);
    }

    // === удобная обёртка: старт трека по готовому TradeSignal ===
    public String trackSignal(signal.TradeSignal ts, state.SymbolState s) {
        // NOTE: если в твоём TradeSignal поле названо иначе (напр. funding),
        // замени ts.fundingRate() на ts.funding()
        SignalSnapshot snap = new SignalSnapshot(
                System.currentTimeMillis(),
                ts.price(),
                ts.oiNow(),
                ts.volNow(),
                ts.buyRatio(),
                ts.voltRel(),
                ts.fundingRate(), // <-- см. примечание выше
                "initial",
                0.0,  // peakProfit начально 0
                0.0   // drawdown   начально 0
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

    /** true, если все активные записи завершены и выгружены */
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

        // финализация после N раундов (+ небольшой запас)
        scheduler.schedule(() -> finishTracking(id),
                (long) SNAPSHOT_INTERVAL_SECONDS * (SNAPSHOT_ROUNDS + 1),
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

    public void forceFinish(String id) {
        finishTracking(id);
    }

    // завершить и экспортировать
    public void finishTracking(String id) {
        SignalRecord r = records.get(id);
        if (r.completed) return;
        if (r == null) return;
        ScheduledFuture<?> f = scheduledTasks.remove(id);
        if (f != null) f.cancel(false);
        r.completed = true;

        if (AUTO_EXPORT_ON_COMPLETE) {
            try { exportRecord(r); } catch (Exception e) { e.printStackTrace(); }

            // --- DB + AutoTuner UPDATE ---


                try {
                    Class.forName("org.sqlite.JDBC");
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }

//            try (var repo = new stats.SignalSqlRepository("jdbc:sqlite:signals.db")) {
//
//                // 1) сохранить сам сигнал
//                repo.upsertSignal(r);
//
//                // 2) сохранить снапшоты
//                repo.insertSnapshots(r.id, r.snapshots);
//
            boolean isMicro = r.isMicro; // из TradeSignal
            AutoTuner.getInstance().onSignalFinished(
                    isMicro ? AutoTuner.Profile.MICRO : AutoTuner.Profile.GLOBAL,
                    r.maxReturn * 100.0,
                    r.minReturn * 100.0
            );

                tuning.AutoTuner.getInstance().applyToFilters("GLOBAL");

                // 4) лог параметров авто-тюнера (для анализа)
                var p = tuning.AutoTuner.getInstance().getParams("GLOBAL");
                repo.insertParamsSnapshot(
                        "GLOBAL", System.currentTimeMillis(),
                        p.minStreak, p.minVolumeSpikeX, p.minDominance, p.minAbsVolumeUsd,
                        p.ewmaWinRate, p.ewmaPeakProfit, p.ewmaDrawdown, p.ewmaThroughput
                );


                repo.commit();
            } catch (Exception e) { e.printStackTrace(); }
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
            pw.printf("%s,%s,%d,%s,%s,%.8f,%.4f,%b%n",
                    r.id, r.symbol, r.createdAtMs, r.stage, r.direction, r.initPrice, r.initScore, r.isMicro);
            pw.println();
            pw.println("snapshot_ts,price,oiNow,volNow,buyRatio,voltRel,funding,peakProfit,drawdown,note");

            synchronized (r.snapshots) {
                for (SignalSnapshot s : r.snapshots) {
                    pw.printf("%d,%.8f,%.2f,%.2f,%.4f,%.4f,%.8f,%.4f,%.4f,%s%n",
                            s.timestampMs, s.price, s.oiNow, s.volNow, s.buyRatio, s.voltRel, s.funding,
                            s.peakProfit, s.drawdown, sanitize(s.note));
                }
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

            // Текущая доходность относительно цены входа
            double currPrice = m.price;
            double ret = (currPrice / r.initPrice - 1.0);

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
                    null,
                    r.maxReturn * 100.0,   // peak profit, %
                    r.minReturn * 100.0    // drawdown, %
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
