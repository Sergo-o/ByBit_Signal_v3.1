package core;

import app.Settings;
import filters.AdaptiveAggressorFilter;
import filters.AggressorBurstFilter;
import filters.OIAccelerationFilter;
import ml.MicroNN;
import debug.DebugPrinter;
import signal.SignalStrength;
import signal.Stage;
import signal.TradeSignal;
import state.SymbolState;
import stats.SignalSnapshot;
import stats.SignalStatsService;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static app.Settings.*;

public class PumpLiquidityAnalyzer {

    private static final int WINDOW_MINUTES = 60;

    // Весовые (как у вас)
    private static final double W_VOL   = 0.35;
    private static final double W_OI    = 0.35;
    private static final double W_DELTA = 0.20;
    private static final double W_VOLT  = 0.10;

    // Пороговые (как у вас)
    private static final double BASE_THRESHOLD_LIGHT = 0.45;
    private static final double BASE_THRESHOLD_HEAVY = 0.50;
    private static final int    ENTER_PERSISTENCE    = 1;
    private static final double ENTER_MULTIPLIER     = 1.00;

    // EWMA
    private static final double EWMA_ALPHA_FAST = 0.30;
    private static final double EWMA_ALPHA_SLOW = 0.08;

    private final Map<String, SymbolState> state = new ConcurrentHashMap<>();
    private final long startTime = System.currentTimeMillis();

    public PumpLiquidityAnalyzer(Map<String, SymbolState> boot) {
        if (boot != null) state.putAll(boot);
    }

    // --- доступы, чтобы другие модули могли читать состояние ---
    public Map<String, SymbolState> getState() { return state; }
    public SymbolState getSymbolState(String symbol) { return state.get(symbol); }

    // === MARKET FEEDS ===

    // KLINE закрытие бара
    public void onKline(String symbol, double close, double volumeUsd, double oiUsd, double funding) {
        SymbolState s = state.computeIfAbsent(symbol, k -> new SymbolState());
        synchronized (s) {
            s.lastPrice   = close;
            s.lastFunding = funding;

            s.closes.addLast(close);
            if (s.closes.size() > Math.max(WINDOW_MINUTES, 60)) s.closes.removeFirst();

            s.volumes.addLast(volumeUsd);
            if (s.volumes.size() > WINDOW_MINUTES) s.volumes.removeFirst();

            s.oiList.addLast(oiUsd);
            if (s.oiList.size() > WINDOW_MINUTES) s.oiList.removeFirst();

            // EWMA avgVol
            s.avgVolUsd = ewma(s.avgVolUsd, volumeUsd, EWMA_ALPHA_SLOW);
            // EWMA avgOI
            s.avgOiUsd  = ewma(s.avgOiUsd,  oiUsd,     EWMA_ALPHA_SLOW);

            // мгновенная волатильность и её EWMA
            double volt = 0.0;
            if (s.closes.size() >= 2) {
                Iterator<Double> it = s.closes.descendingIterator();
                double a = it.next();
                double b = it.next();
                if (b > 0) volt = Math.abs(a / b - 1.0);
            }
            s.avgVolatility = ewma(s.avgVolatility, volt, EWMA_ALPHA_SLOW);

            // сброс минутных потоков агрессора — если вы сбрасываете именно на границе бара
            s.buyAgg1m  = 0.0;
            s.sellAgg1m = 0.0;
        }
    }

    // Тики (агрессор)
    public void onTrade(String symbol, boolean isBuyerMaker, double usd) {
        // isBuyerMaker=true => продажа (т.к. покупатель агрессор), но у вас принята своя интерпретация:
        // ниже оставляем как было — buy=true означает тики «в лонг»
        boolean buyAgg = !isBuyerMaker;

        SymbolState s = state.computeIfAbsent(symbol, k -> new SymbolState());
        synchronized (s) {
            // накопители минутных потоков
            if (buyAgg) s.buyAgg1m  += usd;
            else        s.sellAgg1m += usd;

            // скользящая средняя по тиковому объёму
            s.avgAggressorVol = ewma(s.avgAggressorVol, usd, EWMA_ALPHA_FAST);

            // хвосты
            s.aggressorVolumes.addLast(usd);
            s.aggressorDirections.addLast(buyAgg);
            if (s.aggressorVolumes.size() > 120) s.aggressorVolumes.removeFirst();
            if (s.aggressorDirections.size() > 120) s.aggressorDirections.removeFirst();
        }
    }

    // Ликвидации (BybitWsClient вызывает onLiquidation(symbol, side, price, size*priceUSD))
    public void onLiquidation(String symbol, String side, double price, double usd) {
        SymbolState s = state.computeIfAbsent(symbol, k -> new SymbolState());
        synchronized (s) {
            // В вашей логике:
            // side == "Buy" — ликвидированы лонги (sell pressure) -> учитываем как sell side
            if ("Buy".equalsIgnoreCase(side)) {
                s.liqSell1m = ewma(s.liqSell1m, usd, EWMA_ALPHA_FAST);
            } else {
                s.liqBuy1m  = ewma(s.liqBuy1m, usd, EWMA_ALPHA_FAST);
            }
        }
    }

    // === CORE ANALYZE ===
    public Optional<TradeSignal> analyze(String symbol) {
        SymbolState s = state.get(symbol);
        if (s == null) return Optional.empty();

        synchronized (s) {
            if (s.closes.size() < MIN_BARS_FOR_ANALYSIS) return Optional.empty();
            if (System.currentTimeMillis() - startTime < 60_000) return Optional.empty();

            // 1) OI-фильтр (в режиме обучения — пропускаем)
            if (!Settings.OI_TRAINING_MODE) {
                if (!OIAccelerationFilter.pass(s)) {
                    DebugPrinter.printIgnore(symbol, "[Filter] OIAcceler");
                    return Optional.empty();
                }
            }

            // 2) базовые отсечки по ликвидности
            double oiNow = (s.oiList.peekLast() != null) ? s.oiList.peekLast() : 0.0;
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

            // 3) нормированные метрики
            double volNow = (s.volumes.peekLast() != null) ? s.volumes.peekLast() : 0.0;
            double volRel = safeRatio(volNow, s.avgVolUsd);
            double oiRel  = safeRatio(oiNow,  s.avgOiUsd);

            double buyRatio    = (flow > 0) ? (s.buyAgg1m / flow) : 0.5;
            boolean isLong     = buyRatio > 0.5;
            double deltaShift  = Math.max(0, buyRatio - s.avgDeltaBuy);

            double currVolt = volatility(s);
            double voltRel  = safeRatio(currVolt, s.avgVolatility);

            double longScore =
                    W_VOL * cap(volRel) +
                            W_OI  * cap(oiRel)  +
                            W_DELTA * cap(deltaShift / 0.10) +
                            W_VOLT  * cap(voltRel / 2.0);

            String bias = (longScore >= 0.5) ? "LONG" : "SHORT";

            // 4) фильтр агрессора (в режиме обучения — пропускаем)
            if (!Settings.OI_TRAINING_MODE) {
                if (!AdaptiveAggressorFilter.pass(s, longScore >= 0.5, symbol)) {
                    DebugPrinter.printIgnore(symbol, "[Filter] AdaptiveAggr");
                    return Optional.empty();
                }
            }

            // 5) micro модель (если включена)
            boolean isMicro = oiNow < MICRO_OI_USD;
            if (MICRO_NN_ENABLED && isMicro) {
                double p = MicroNN.predict(s, isLong);
                if (p < MICRO_NN_THRESHOLD) {
                    DebugPrinter.printIgnore(symbol, String.format("MicroNN reject p=%.2f", p));
                    return Optional.empty();
                }
            }

            // 6) burst фильтр (в режиме обучения — пропускаем)
            if (!Settings.OI_TRAINING_MODE) {
                if (!AggressorBurstFilter.pass(s, isLong)) {
                    DebugPrinter.printIgnore(symbol, "No aggressor burst");
                    return Optional.empty();
                }
            }

            // 7) персистентность/кулдаун
            double base = isHeavy ? BASE_THRESHOLD_HEAVY : BASE_THRESHOLD_LIGHT;
            double watchT = base;
            double enterT = watchT * ENTER_MULTIPLIER;
            long now = System.currentTimeMillis();

            if (now < s.cooldownUntil) return Optional.empty();
            if (now - s.lastSignalAtMs < MIN_SIGNAL_GAP_MS) return Optional.empty();

            double score = longScore;
            SignalStrength strength = (score < 0.40) ? SignalStrength.WEAK :
                    (score < 0.60) ? SignalStrength.MEDIUM : SignalStrength.STRONG;

            // 8) строим сигнал
            TradeSignal sig = new TradeSignal(
                    symbol, Stage.ENTER, bias,
                    s.lastPrice, score, strength,
                    reason("ENTER", volRel, oiRel, buyRatio, voltRel),
                    oiNow, volNow, buyRatio, voltRel, s.lastFunding,
                    isMicro
            );

            // === Трекинг статистики и автоснапшоты ===
            SignalStatsService.getInstance().trackSignal(sig, s);

            // cooldown / housekeeping
            s.cooldownUntil = now + (isHeavy ? COOLDOWN_MS_HEAVY : COOLDOWN_MS_LIGHT);
            s.lastSignalAtMs = now;
            s.watchStreak = 0;
            s.enterStreak = 0;

            DebugPrinter.monitor(symbol, s, score);
            return Optional.of(sig);
        }
    }

    // === helpers ===

    private static double ewma(double prev, double x, double alpha) {
        if (prev == 0.0) return x;
        return prev * (1.0 - alpha) + x * alpha;
    }

    private static double volatility(SymbolState s) {
        if (s.closes.size() < 2) return 0.0;
        Iterator<Double> it = s.closes.descendingIterator();
        double a = it.next();
        double b = it.next();
        return (b > 0.0) ? Math.abs(a / b - 1.0) : 0.0;
    }

    private static double cap(double x) {
        if (x < 0) return 0;
        if (x > 3.0) return 3.0;
        return x;
    }

    private static double clamp01(double x) {
        if (x < 0) return 0;
        if (x > 1) return 1;
        return x;
    }

    private static double safeRatio(double a, double b) {
        return (b > 0.0) ? (a / b) : 0.0;
    }

    private static String reason(String tag, double volRel, double oiRel, double buyRatio, double voltRel) {
        return String.format(
                "%s | vol×=%.2f oi×=%.2f buy=%.2f volt×=%.2f",
                tag, volRel, oiRel, buyRatio, voltRel
        );
    }

    // если где-то нужно было сбрасывать streak'и
    private static void reset(SymbolState s) {
        s.watchStreak = 0;
        s.enterStreak = 0;
    }
}
