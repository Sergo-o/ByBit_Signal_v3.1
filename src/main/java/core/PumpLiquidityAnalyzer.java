package core;

import debug.DebugPrinter;
import filters.*;
import log.FilterLog;
import ml.MicroNN;
import signal.*;
import state.*;
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
        if (x < 0) return 0;
        if (x > 1) return 1;
        return x;
    }

    // ===== входы потока =====

    /**
     * Тиковые сделки: агрессор + USD объём
     */
    public void onTrade(String symbol, boolean isBuy, double usd) {
        SymbolState s = state.computeIfAbsent(symbol, k -> new SymbolState());
        synchronized (s) {
            // очередь направлений/объёмов агрессора
            s.aggressorDirections.addLast(isBuy);
            s.aggressorVolumes.addLast(usd);

            while (s.aggressorDirections.size() > Settings.MAX_TRADE_WINDOW) {
                s.aggressorDirections.removeFirst();
            }
            while (s.aggressorVolumes.size() > Settings.MAX_TRADE_WINDOW) {
                s.aggressorVolumes.removeFirst();
            }

            // средний тиковый объём агрессора
            s.avgAggressorVol = ewma(s.avgAggressorVol, usd, Settings.EWMA_ALPHA_FAST);

            // минутные агрегаты для buy/sell агрессора
            if (isBuy) s.buyAgg1m += usd;
            else s.sellAgg1m += usd;
        }
    }

    /**
     * Минутные свечи: цена/объём/USD + OI + funding на момент закрытия бара
     */
    public void onKline(String symbol, double close, double volumeUsd, double oiUsd, double funding) {
        SymbolState s = state.computeIfAbsent(symbol, k -> new SymbolState());
        synchronized (s) {
            s.lastPrice = close;
            s.lastFunding = funding;

            // цены
            s.closes.addLast(close);
            while (s.closes.size() > Math.min(Settings.MAX_BAR_HISTORY,
                    Math.max(Settings.WINDOW_MINUTES, Settings.MIN_BAR_HISTORY))) {
                s.closes.removeFirst();
            }

            s.volumes.addLast(volumeUsd);
            while (s.volumes.size() > Math.min(Settings.MAX_BAR_HISTORY, Settings.WINDOW_MINUTES)) {
                s.volumes.removeFirst();
            }

            s.oiList.addLast(oiUsd);
            while (s.oiList.size() > Math.min(Settings.MAX_BAR_HISTORY, Settings.WINDOW_MINUTES)) {
                s.oiList.removeFirst();
            }

            // EWMA среднего объёма/мин и среднего OI
            s.avgVolUsd = ewma(s.avgVolUsd, volumeUsd, Settings.EWMA_ALPHA_SLOW);
            s.avgOiUsd = ewma(s.avgOiUsd, oiUsd, Settings.EWMA_ALPHA_SLOW);

            // волатильность (|r| за бар), затем EWMA
            double volt = 0.0;
            if (s.closes.size() >= 2) {
                var it = s.closes.descendingIterator();
                double a = it.next();
                double b = it.next();
                if (b > 0) volt = Math.abs(a / b - 1.0);
            }
            s.avgVolatility = ewma(s.avgVolatility, volt, Settings.EWMA_ALPHA_SLOW);

            // === Snapshot для ReversalWatchService до сброса агрессора ===
            double volNow = volumeUsd;
            double oiNow  = oiUsd;

            double avgVol = s.avgVolUsd;
            double avgOi  = s.avgOiUsd;

            double volRel = avgVol > 0 ? volNow / avgVol : 0.0;
            double oiRel  = avgOi  > 0 ? oiNow  / avgOi  : 0.0;

            double flow = s.buyAgg1m + s.sellAgg1m;
            double buyRatio;
            if (flow > 0.0) {
                buyRatio = s.buyAgg1m / flow;
            } else {
                buyRatio = 0.5;
            }

            MarketSnapshot snap = new MarketSnapshot(
                    volNow,
                    volRel,
                    oiNow,
                    oiRel,
                    flow,
                    buyRatio,
                    0.0,                // deltaShift, если не считаешь — оставляем 0
                    s.avgVolatility,
                    0.0                 // score в этом контексте неважен
            );

            // прогоняем через watcher все активные сигналы по этому symbol
            ReversalWatchService.getInstance().onKline(symbol, s, snap);

            // сброс минутного «живого» потока агрессора — начинается новый бар
            s.buyAgg1m = 0.0;
            s.sellAgg1m = 0.0;

            // (при желании здесь же можно закрывать минутные streak'и)
        }
    }

    /**
     * Ликвидации (на Bybit: side="Buy"/"Sell").
     */
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
            long now = System.currentTimeMillis();

            // =========================
            // 0. Минимальные условия
            // =========================
            if (s.closes.size() < MIN_BARS_FOR_ANALYSIS) {
                return Optional.empty();
            }
            // небольшой прогрев всего анализатора
            if (now - startTime < 60_000L) {
                return Optional.empty();
            }

            // =========================
            // 1. Базовые метрики по монете
            // =========================
            double oiNow = s.oiList.getLast();
            double volNow = s.volumes.getLast();
            double flow = s.buyAgg1m + s.sellAgg1m;

            double avgVol = s.avgVolUsd;
            double avgOi = s.avgOiUsd;
            double volRel = avgVol > 0 ? volNow / avgVol : 0.0;
            double oiRel = avgOi > 0 ? oiNow / avgOi : 0.0;

            double buyRatio;
            if (flow > 0.0) {
                buyRatio = s.buyAgg1m / flow;
            } else {
                buyRatio = 0.5;
            }

            boolean isLong = buyRatio > 0.5;
            boolean isHeavy = SEED_HEAVY.contains(symbol) || s.avgVolUsd >= 5_000_000;
            boolean isMicro = oiNow < MICRO_OI_USD;

            // ======================
            // 2. Базовые фильтры
            // ======================

            // 2.1. OI по профилю
            double minOi = isHeavy ? MIN_OI_HEAVY : MIN_OI_LIGHT;
            if (oiNow < minOi) {
//                String reason = String.format(
//                        "Низкий OI: %d (min=%.0f, heavy=%s, micro=%s)",
//                        (long) oiNow, minOi, isHeavy, isMicro
//                );
//                DebugPrinter.printIgnore(symbol, reason);
//                FilterLog.logIgnore(symbol, reason);
                return Optional.empty();
            }


            // 2.2. Поток агрессора
            double minFlowBase = Math.max(MIN_FLOW_FLOOR, avgVol * MIN_FLOW_RATIO);
            double minFlow = minFlowBase;

            // Для heavy-монет можно быть мягче по расходу:
            // они сами по себе ликвидные, даже если текущая минута чуть тише
            if (isHeavy) {
                minFlow = minFlowBase * 0.5;    // 50% от базового порога
            }
            // Для микро-кап — наоборот, чуть жестче
            else if (isMicro) {
                minFlow = minFlowBase * 1.2;    // +20% к порогу
            }

            if (flow < minFlow) {
//                String reason = String.format(
//                        "Низкий расход: %d (min=%.0f, avgVol=%.0f, heavy=%s, micro=%s)",
//                        (long) flow, minFlow, avgVol, isHeavy, isMicro
//                );
//                DebugPrinter.printIgnore(symbol, reason);
//                FilterLog.logIgnore(symbol, reason);
                return Optional.empty();
            }


            // 2.3. Направление потока (слишком нейтральное направление)
            double dirOffset = Math.abs(buyRatio - 0.5); // 0 = нейтрально, 0.5 = чистый one-side
            if (dirOffset < MIN_FLOW_RATIO) {
//                DebugPrinter.printIgnore(symbol,
//                        String.format("Слабое направление, buyRatio=%.2f", buyRatio));
                return Optional.empty();
            }

            // 2.4. Cooldown и минимальный разрыв между сигналами
            if (now < s.getCooldownUntil()) {
                DebugPrinter.printIgnore(symbol, "Перезарядка активна");
                return Optional.empty();
            }

            if (s.getLastSignalAtMs() > 0 && now - s.getLastSignalAtMs() < MIN_SIGNAL_GAP_MS) {
                DebugPrinter.printIgnore(symbol, "Слишком частые сигналы");
                return Optional.empty();
            }

            // =========================
            // 3. Фильтры со score
            // =========================
            int score = 0;

            // -------------------------
            // 3.1. OI Acceleration Filter
            // -------------------------
            if (OI_FILTER_ENABLED) {
                boolean ok = OIAccelerationFilter.pass(s, symbol);
                if (ok) {
                    score++;
                } else if (!OI_SOFT_MODE && !OI_TRAINING_MODE) {
//                    DebugPrinter.printIgnore(symbol, "[Filter] OIAcceleration");
                    return Optional.empty();
                }
                // если OI_SOFT_MODE или OI_TRAINING_MODE — можем не выкидывать, а просто не добавлять score
            }

            // -------------------------
            // 3.2. Aggressor Filter
            // -------------------------
            if (AGGRESSOR_FILTER_ENABLED) {
                boolean ok = AdaptiveAggressorFilter.pass(s, isLong, symbol);
                if (ok) {
                    score++;
                } else if (!AGGRESSOR_SOFT_MODE) {
//                    DebugPrinter.printIgnore(symbol, "[Filter] AdaptiveAggressor");
                    return Optional.empty();
                }
            }

            // -------------------------
            // 3.3. MicroNN (только для микро-кап)
            // -------------------------
            if (MICRO_NN_ENABLED && isMicro) {
                double p = MicroNN.predict(s, isLong);
                if (p < MICRO_NN_THRESHOLD) {
//                    DebugPrinter.printIgnore(symbol,
//                            String.format("MicroNN reject p=%.2f", p));
                    return Optional.empty();
                }
                // по желанию можно добавить: score++;
            }

            // -------------------------
            // 3.4. Burst Filter
            // -------------------------
            if (BURST_FILTER_ENABLED) {
                boolean ok = AggressorBurstFilter.pass(s, isLong, symbol);
                if (ok) {
                    score++;
                } else if (!BURST_SOFT_MODE) {
//                    DebugPrinter.printIgnore(symbol, "[Filter] Burst");
                    return Optional.empty();
                }
            }

            // ==========================
            // 4. Сила сигнала по score
            // ==========================
            SignalStrength strength;
            if (score >= 3) {
                strength = SignalStrength.STRONG;
            } else if (score == 2) {
                strength = SignalStrength.MEDIUM;
            } else if (score == 1) {
                strength = SignalStrength.WEAK;
            } else {
                DebugPrinter.printIgnore(symbol, "Низкая оценка =" + score);
                return Optional.empty();
            }

            // ==========================
            // 5. Собираем сигнал
            // ==========================

            TradeSignal sig = new TradeSignal(
                    symbol,
                    Stage.ENTER,                       // если у тебя другой Stage — подставь
                    isLong ? "LONG" : "SHORT",
                    s.lastPrice,
                    score,
                    strength,
                    "ENTER (score=" + score + ")",
                    oiNow,
                    flow,
                    buyRatio,
                    s.avgVolatility,
                    s.lastFunding,
                    isMicro
            );

            // === Снапшот для статистики ===
            SignalSnapshot snap = new SignalSnapshot(
                    now,
                    s.lastPrice,
                    oiNow,
                    flow,
                    buyRatio,
                    s.avgVolatility,
                    s.lastFunding,
                    "enter (score=" + score + ")",
                    0.0,
                    0.0
            );

// === Трекинг сигнала + id ===
            SignalStatsService stats = SignalStatsService.getInstance();
            String signalId = stats.trackSignal(
                    symbol,
                    "ENTER",
                    isLong ? "LONG" : "SHORT",
                    s.lastPrice,
                    score,
                    isMicro,
                    snap
            );

            // === MarketSnapshot для фильтра ===
            MarketSnapshot ms = new MarketSnapshot(
                    volNow,
                    volRel,
                    oiNow,
                    oiRel,
                    flow,
                    buyRatio,
                    0.0,                 // deltaShift, если нужно — подставишь своё
                    s.avgVolatility,     // voltRel / волатильность
                    score                // текущий score
            );

            // === Проверка на фейк-сигнал ===
            FakeSignalFilter fakeFilter = new FakeSignalFilter(isLong ? "LONG" : "SHORT");
            boolean passFake = fakeFilter.pass(symbol, s, ms);
            if (!passFake) {
                // помечаем сигнал как фейковый для экспорта
                stats.markAsFake(signalId);
                // можно тут же завершить трекинг, если не хочешь ждать SNAPSHOT_ROUNDS:
                // stats.finishTracking(signalId);
                return Optional.empty();
            }

            // === Старт наблюдения за разворотом по этому сигналу ===
            ReversalWatchService.getInstance().startWatch(
                    signalId,
                    symbol,
                    isLong,
                    now
            );

            // ==========================
            // 6. Обновляем состояние
            // ==========================
            long cooldownMs = isHeavy ? COOLDOWN_MS_HEAVY : COOLDOWN_MS_LIGHT;
            s.setCooldownUntil(now + cooldownMs);
            s.setLastSignalAtMs(now);

            s.setWatchStreak(0);
            s.setEnterStreak(0);

            return Optional.of(sig);
        }
    }

}

