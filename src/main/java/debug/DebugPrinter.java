package debug;

import app.Settings;
import core.PumpLiquidityAnalyzer;
import log.FilterLog;
import state.SymbolState;

import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

public class DebugPrinter {

    private static final long PRINT_INTERVAL = 10_000;
    private static final long ANALYZE_INTERVAL_MS = 3_000;
    private static final long IMPULSE_INTERVAL_MS = 5_000;

    private static long lastPrint = 0;
    private static long lastPrintAnalyze = 0;
    private static final Map<String, Long> lastImpulseAt = new HashMap<>();

    private static boolean isDebugCoin(String symbol) {
        return Settings.DEBUG_COINS.contains(symbol);
    }

    public static void tryPrint(String symbol, SymbolState s, double score) {
        if (!isDebugCoin(symbol)) return;
        long now = System.currentTimeMillis();
        if (now - lastPrint < PRINT_INTERVAL) return;
        lastPrint = now;

        double volRel = safeRel(s.volumes, s.avgVolUsd);
        double oiRel  = safeRel(s.oiList, s.avgOiUsd);
        double flow   = s.buyAgg1m + s.sellAgg1m;

        System.out.printf(
                "[DBG] %s | price=%.4f | vol=%.2fx | oi=%.2fx | flow=%.0f | score=%.2f%n",
                symbol, s.lastPrice, volRel, oiRel, flow, score
        );
    }

    public static void printIgnore(String symbol, String reason) {
        if (!isDebugCoin(symbol)) return;
        FilterLog.logIgnore(symbol, reason);
    }

    public static void monitor(String symbol, SymbolState s, double score) {
        if (!isDebugCoin(symbol)) return;
        long now = System.currentTimeMillis();
        if (now - lastPrintAnalyze < ANALYZE_INTERVAL_MS) return;
        lastPrintAnalyze = now;

        double volRel = safeRel(s.volumes, s.avgVolUsd);
        double oiRel  = safeRel(s.oiList, s.avgOiUsd);
        double flow   = s.buyAgg1m + s.sellAgg1m;
        double buy    = (flow > 0) ? (s.buyAgg1m / flow) : 0.5;

        System.out.printf("[MONITOR] %s | bars=%d | vol %.0f / avg %.0f | OI %.0f / avg %.0f | flow=%.0f | buy=%.2f | score=%.2f%n",
                symbol, s.closes.size(),
                (s.volumes.peekLast()!=null?s.volumes.peekLast():0.0), s.avgVolUsd,
                (s.oiList.peekLast()!=null?s.oiList.peekLast():0.0), s.avgOiUsd,
                flow, buy, score);
    }

    public static void printImpulse(String symbol, String msg) {
        if (!isDebugCoin(symbol)) return;
        long now = System.currentTimeMillis();
        long last = lastImpulseAt.getOrDefault(symbol, 0L);
        if (now - last < IMPULSE_INTERVAL_MS) return;
        lastImpulseAt.put(symbol, now);
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.printf("[IMPULSE] %s — %s%n", symbol, msg);
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    public static void printImpulseCheck(String symbol,
                                         boolean strong,
                                         boolean oiPulse,
                                         boolean volBurst,
                                         boolean liqSupport,
                                         boolean priceImpulse,
                                         String... extraMetrics) {
        if (!isDebugCoin(symbol)) return;

        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.printf("[IMPULSE] %s — %s%n", symbol, strong ? "✅ СИЛЬНЫЙ" : "❌ СЛАБЫЙ");
        System.out.println();
        System.out.println("Компоненты:");
        System.out.printf("  • OI импульс:          %s%n", oiPulse ? "да" : "нет");
        System.out.printf("  • Всплеск объёма:      %s%n", volBurst ? "да" : "нет");
        System.out.printf("  • Ликвидации рядом:    %s%n", liqSupport ? "да" : "нет");
        System.out.printf("  • Ценовой рывок > σ:   %s%n", priceImpulse ? "да" : "нет");
        if (extraMetrics != null && extraMetrics.length > 0) {
            System.out.println();
            System.out.println("Метрики:");
            for (String m : extraMetrics) System.out.println("  • " + m);
        }
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    private static double safeRel(Deque<Double> d, double avg) {
        Double v = d.peekLast();
        if (v == null || avg <= 0) return 0;
        return v / avg;
    }
}

