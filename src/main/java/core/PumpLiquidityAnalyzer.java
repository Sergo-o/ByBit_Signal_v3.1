package core;

import app.Settings;
import filters.AggressorBurstFilter;
import filters.AdaptiveAggressorFilter;
import filters.OIAccelerationFilter;
import ml.MicroNN;
import signal.SignalStrength;
import signal.TradeSignal;
import signal.Stage;
import state.SymbolState;
import debug.DebugPrinter;
import stats.SignalSnapshot;
import stats.SignalStatsService;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static app.Settings.*;

public class PumpLiquidityAnalyzer {

    private final Map<String, SymbolState> state = new ConcurrentHashMap<>();
    private final long startTime = System.currentTimeMillis();

    public SymbolState getSymbolState(String symbol) {
        return state.get(symbol);
    }

    public PumpLiquidityAnalyzer(Map<String, SymbolState> boot) {
        state.putAll(boot);
    }

    // ============================================
    //               DATA FEEDS
    // ============================================

    public void onKline(String symbol, double close, double volumeUsd, double oiUsd, double funding) {
        SymbolState s = state.computeIfAbsent(symbol, k -> new SymbolState());
        synchronized (s) {
            s.lastPrice = close;
            s.lastFunding = funding;

            // price
            s.closes.add(close);
            if (s.closes.size() > WINDOW_MINUTES) s.closes.removeFirst();

            // volume
            s.volumes.add(volumeUsd);
            if (s.volumes.size() > WINDOW_MINUTES) s.volumes.removeFirst();

            // OI
            s.oiList.add(oiUsd);
            if (s.oiList.size() > WINDOW_MINUTES) s.oiList.removeFirst();

            // EWMA volume
            s.avgVolUsd = ewma(s.avgVolUsd, volumeUsd, EWMA_ALPHA_SLOW);

            // EWMA volatility
            double volt = 0;
            if (s.closes.size() >= 2) {
                var it = s.closes.descendingIterator();
                double a = it.next(), b = it.next();
                if (b > 0) volt = Math.abs(a / b - 1);
            }
            s.avgVolatility = ewma(s.avgVolatility, volt, EWMA_ALPHA_SLOW);

            // EWMA OI
            s.avgOiUsd = ewma(s.avgOiUsd, oiUsd, EWMA_ALPHA_SLOW);

            // reset live tick flow per bar
            s.buyAgg1m = 0;
            s.sellAgg1m = 0;

            s.liqBuy1m = 0.0;
            s.liqSell1m = 0.0;
        }
    }

    public void onTrade(String symbol, boolean isBuy, double usd) {
        SymbolState s = state.computeIfAbsent(symbol, k -> new SymbolState());
        synchronized (s) {
            if (isBuy) {
                s.buyAgg1m += usd;
                s.aggressorDirections.add(true);
            } else {
                s.sellAgg1m += usd;
                s.aggressorDirections.add(false);
            }
            s.aggressorVolumes.add(usd);

            trimDeque(s.aggressorDirections, 50);
            trimDeque(s.aggressorVolumes, 50);
        }
    }

    public void onLiquidation(String symbol, boolean longSideWasLiquidated, double usd) {
        SymbolState s = state.computeIfAbsent(symbol, k -> new SymbolState());
        synchronized (s) {
            if (longSideWasLiquidated) {
                // ликвидировали лонги → продавцы давят вниз
                s.liqSell1m = ewma(s.liqSell1m, usd, EWMA_ALPHA_FAST);
            } else {
                // ликвидировали шорты → покупатели толкают вверх
                s.liqBuy1m = ewma(s.liqBuy1m, usd, EWMA_ALPHA_FAST);
            }
        }
    }


    // ============================================
    //               ANALYSIS LOGIC
    // ============================================

    public Optional<TradeSignal> analyze(String symbol) {
        SymbolState s = state.get(symbol);
        if (s == null) return Optional.empty();
        synchronized (s) {
            if (s.closes.size() < MIN_BARS_FOR_ANALYSIS) return Optional.empty();
            if (System.currentTimeMillis() - startTime < 60_000) return Optional.empty();

//            if (!OIAccelerationFilter.pass(s)) {
//                DebugPrinter.printIgnore(symbol, "[Filter] OIAcceler");
//                return Optional.empty();
//            }

            double oiNow = s.oiList.getLast();
            boolean isHeavy = SEED_HEAVY.contains(symbol) || s.avgVolUsd >= 5_000_000;
            double minOi = isHeavy ? MIN_OI_HEAVY : MIN_OI_LIGHT;
            if (oiNow < minOi) {
                DebugPrinter.printIgnore(symbol, "Low OI: " + (long) oiNow);
                return Optional.empty();
            }

            double flow = s.buyAgg1m + s.sellAgg1m;
            double minFlow = Math.max(MIN_FLOW_FLOOR, s.avgVolUsd * MIN_FLOW_RATIO);
            if (flow < minFlow) {
                DebugPrinter.printIgnore(symbol, "Low flow: " + (long) flow);
                return Optional.empty();
            }

            double buyRatio = flow > 0 ? (s.buyAgg1m / flow) : 0.5;
            boolean isLong = buyRatio > 0.5;

//            if (!AdaptiveAggressorFilter.pass(s, isLong, symbol)) {
//                DebugPrinter.printIgnore(symbol, "[Filter] AdaptiveAggr");
//                return Optional.empty();
//            }

            // micro model
            boolean isMicro = oiNow < MICRO_OI_USD;
            if (MICRO_NN_ENABLED && isMicro) {
                double p = MicroNN.predict(s, isLong);
                if (p < MICRO_NN_THRESHOLD) {
                    DebugPrinter.printIgnore(symbol, String.format("MicroNN reject p=%.2f", p));
                    return Optional.empty();
                }
            }

//            if (!AggressorBurstFilter.pass(s, isLong)) {
//                DebugPrinter.printIgnore(symbol, "No aggressor burst");
//                return Optional.empty();
//            }

            // build signal
            TradeSignal sig = new TradeSignal(
                    symbol, Stage.ENTER, isLong ? "LONG" : "SHORT",
                    s.lastPrice, 0.0, SignalStrength.STRONG,
                    "ENTER CONFIRMED",
                    oiNow, flow, buyRatio, s.avgVolatility, s.lastFunding,
                    isMicro
            );

            // запись сигнала + старт автоснимков (JSON/CSV)
            SignalStatsService.getInstance().trackSignal(sig, s);

// cooldown / housekeeping
            s.cooldownUntil = System.currentTimeMillis() + (isHeavy ? COOLDOWN_MS_HEAVY : COOLDOWN_MS_LIGHT);
            s.lastSignalAtMs = System.currentTimeMillis();
            s.watchStreak = 0;
            s.enterStreak = 0;

            return Optional.of(sig);
        }
    }

    // ============================================
    //               HELPERS
    // ============================================

    private static double ewma(double prev, double x, double alpha) {
        return (prev == 0) ? x : prev * (1 - alpha) + x * alpha;
    }

    private static void trimDeque(java.util.Deque<?> dq, int max) {
        while (dq.size() > max) dq.removeFirst();
    }
}
