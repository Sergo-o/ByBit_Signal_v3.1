package core;

import debug.DebugPrinter;
import filters.AggressorBurstFilter;
import filters.AdaptiveAggressorFilter;
import filters.OIAccelerationFilter;
import ml.MicroNN;
import signal.SignalStrength;
import signal.Stage;
import signal.TradeSignal;
import state.SymbolState;
import stats.SignalSnapshot;
import stats.SignalStatsService;
import app.Settings;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static app.Settings.*;

/**
 * PumpLiquidityAnalyzer — основной анализатор потока.
 * Содержит onTrade / onKline / onLiquidation и analyze().
 * TRAIN-режим управляется Settings.OI_TRAINING_MODE (ослабление/пропуск фильтров).
 */
public class PumpLiquidityAnalyzer {

    private final Map<String, SymbolState> state = new ConcurrentHashMap<>();
    private final long startTime = System.currentTimeMillis();

    // === конструктор как у тебя в проекте ===
    public PumpLiquidityAnalyzer(Map<String, SymbolState> boot) {
        if (boot != null && !boot.isEmpty()) state.putAll(boot);
    }

    // Дай доступ провайдерам метрик (AnalyzerMetricsProvider)
    public SymbolState getSymbolState(String symbol) {
        return state.get(symbol);
    }

    // ===== helpers =====
    private static double ewma(double prev, double x, double alpha) {
        if (prev == 0.0) return x;
        return prev * (1 - alpha) + x * alpha;
    }

    private static double safeRatio(double a, double b) {
        if (b <= 0) return 0.0;
        return a / b;
    }

    private static double clamp01(double x) {
        if (x < 0) return 0; if (x > 1) return 1; return x;
    }

    // ===== входы потока =====

    /** Тиковые сделки: агрессор + USD объём */
    public void onTrade(String symbol, boolean isBuy, double usd) {
        SymbolState s = state.computeIfAbsent(symbol, k -> new SymbolState());
        synchronized (s) {
            // очередь направлений/объёмов агрессора
            s.aggressorDirections.addLast(isBuy);
            s.aggressorVolumes.addLast(usd);
            if (s.aggressorDirections.size() > 64) s.aggressorDirections.removeFirst();
            if (s.aggressorVolumes.size() > 64) s.aggressorVolumes.removeFirst();

            // средний тиковый объём агрессора
            s.avgAggressorVol = ewma(s.avgAggressorVol, usd, Settings.EWMA_ALPHA_FAST);

            // минутные агрегаты для buy/sell агрессора
            if (isBuy) s.buyAgg1m += usd; else s.sellAgg1m += usd;
        }
    }

    /** Минутные свечи: цена/объём/USD + OI + funding на момент закрытия бара */
    public void onKline(String symbol, double close, double volumeUsd, double oiUsd, double funding) {
        SymbolState s = state.computeIfAbsent(symbol, k -> new SymbolState());
        synchronized (s) {
            s.lastPrice   = close;
            s.lastFunding = funding;

            // цены
            s.closes.addLast(close);
            if (s.closes.size() > Math.max(Settings.WINDOW_MINUTES, 60)) s.closes.removeFirst();

            // объём (USD)
            s.volumes.addLast(volumeUsd);
            if (s.volumes.size() > Settings.WINDOW_MINUTES) s.volumes.removeFirst();

            // OI (USD)
            s.oiList.addLast(oiUsd);
            if (s.oiList.size() > Settings.WINDOW_MINUTES) s.oiList.removeFirst();

            // EWMA среднего объёма/мин и среднего OI
            s.avgVolUsd = ewma(s.avgVolUsd, volumeUsd, Settings.EWMA_ALPHA_SLOW);
            s.avgOiUsd  = ewma(s.avgOiUsd,  oiUsd,    Settings.EWMA_ALPHA_SLOW);

            // волатильность (|r| за бар), затем EWMA
            double volt = 0.0;
            if (s.closes.size() >= 2) {
                var it = s.closes.descendingIterator();
                double a = it.next();
                double b = it.next();
                if (b > 0) volt = Math.abs(a / b - 1.0);
            }
            s.avgVolatility = ewma(s.avgVolatility, volt, Settings.EWMA_ALPHA_SLOW);

            // сброс минутного «живого» потока агрессора — начинается новый бар
            s.buyAgg1m  = 0.0;
            s.sellAgg1m = 0.0;

            // (при желании здесь же можно закрывать минутные streak'и)
        }
    }

    /** Ликвидации (на Bybit: side="Buy"/"Sell"). */
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

    // ===== основная логика анализа =====

    public Optional<TradeSignal> analyze(String symbol) {
        SymbolState s = state.get(symbol);
        if (s == null) return Optional.empty();

        synchronized (s) {
            if (s.closes.size() < MIN_BARS_FOR_ANALYSIS) return Optional.empty();
            if (System.currentTimeMillis() - startTime < 60_000) return Optional.empty();

            // ====================== OIAccelerationFilter ======================
            if (!OIAccelerationFilter.pass(s)) {
                DebugPrinter.printIgnore(symbol, "[Filter] OIAcceler");
                return Optional.empty();
            }

            // ====================== Базовые проверки ======================
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

            // ====================== AdaptiveAggressorFilter ======================
            if (app.Settings.AGGR_FILTER_ENABLED) {
                boolean ok = AdaptiveAggressorFilter.pass(s, isLong, symbol);
                if (!ok && !app.Settings.AGGR_TRAIN) {
                    DebugPrinter.printIgnore(symbol, "[Filter] AdaptiveAggr");
                    return Optional.empty();
                }
            }

            // ====================== Micro-модель ======================
            boolean isMicro = oiNow < MICRO_OI_USD;
            if (MICRO_NN_ENABLED && isMicro) {
                double p = MicroNN.predict(s, isLong);
                if (p < MICRO_NN_THRESHOLD) {
                    DebugPrinter.printIgnore(symbol, String.format("MicroNN reject p=%.2f", p));
                    return Optional.empty();
                }
            }

            // ====================== AggressorBurstFilter ======================
            if (app.Settings.BURST_FILTER_ENABLED) {
                boolean ok = AggressorBurstFilter.pass(s, isLong);
                if (!ok && !app.Settings.BURST_TRAIN) {
                    DebugPrinter.printIgnore(symbol, "No aggressor burst");
                    return Optional.empty();
                }
            }

            // ====================== Формируем сигнал ======================
            TradeSignal sig = new TradeSignal(
                    symbol,
                    Stage.ENTER,
                    isLong ? "LONG" : "SHORT",
                    s.lastPrice,
                    0.0,
                    SignalStrength.STRONG,
                    "ENTER CONFIRMED",
                    oiNow,
                    flow,
                    buyRatio,
                    s.avgVolatility,
                    s.lastFunding,
                    isMicro
            );

            // === Запуск трекинга статистики ===
            var snap = new SignalSnapshot(
                    System.currentTimeMillis(),
                    s.lastPrice,
                    oiNow,
                    flow,
                    buyRatio,
                    s.avgVolatility,
                    s.lastFunding,
                    "enter",
                    0.0,
                    0.0
            );

            SignalStatsService.getInstance().trackSignal(
                    symbol,
                    "ENTER",
                    isLong ? "LONG" : "SHORT",
                    s.lastPrice,
                    1.0,
                    isMicro,
                    snap
            );

            // === Хаускипинг / кулдаун ===
            s.cooldownUntil = System.currentTimeMillis() + (isHeavy ? COOLDOWN_MS_HEAVY : COOLDOWN_MS_LIGHT);
            s.lastSignalAtMs = System.currentTimeMillis();
            s.watchStreak = 0;
            s.enterStreak = 0;

            return Optional.of(sig);
        }
    }

}
