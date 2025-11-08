package core;

import app.Settings;
import filters.AdaptiveAggressorFilter;
import filters.OIAccelerationFilter;
import ml.MicroNN;
import state.SymbolState;
import debug.DebugPrinter;

import java.util.*;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static app.Settings.*;

/**
 * Основной анализатор: принимает потоки (klines/trades/liqs) и порождает сигналы.
 * ЛОГИКА СОХРАНЕНА: onKline / onTrade / onLiquidation / analyze
 */
public class PumpLiquidityAnalyzer {

    private final Map<String, SymbolState> state;
    private final long startTime = System.currentTimeMillis();

    public PumpLiquidityAnalyzer() {
        this.state = new ConcurrentHashMap<>();
    }

    public SymbolState getSymbolState(String symbol) {
        return state.get(symbol);
    }


    /* =========================
     *          INPUTS
     * ========================= */

    /** Закрытие 1-мин свёчи + нам пришли текущие OI и funding. */
    public void onKline(String symbol, double close, double volumeUsd, double oiUsd, double funding) {
        SymbolState s = state.computeIfAbsent(symbol, k -> new SymbolState());
        synchronized (s) {
            s.lastPrice   = close;
            s.lastFunding = funding;

            // Снимем "предыдущие" значения ДО добавления новых,
            // чтобы корректно посчитать волатильность и наклон OI.
            Double prevClose = s.closes.peekLast();   // может быть null при первом баре
            Double prevOi    = s.oiList.peekLast();   // может быть null при первом баре

            // --- цены ---
            s.closes.addLast(close);
            if (s.closes.size() > Math.max(WINDOW_MINUTES, 60)) {
                s.closes.removeFirst();
            }

            // --- объём ---
            s.volumes.addLast(volumeUsd);
            if (s.volumes.size() > WINDOW_MINUTES) {
                s.volumes.removeFirst();
            }

            // --- OI ---
            s.oiList.addLast(oiUsd);
            if (s.oiList.size() > WINDOW_MINUTES) {
                s.oiList.removeFirst();
            }

            // EWMA среднего объёма/мин
            s.avgVolUsd = ewma(s.avgVolUsd, volumeUsd, EWMA_ALPHA_SLOW);

            // Волатильность бара (|r|), потом EWMA
            double volt = 0.0;
            if (prevClose != null && prevClose > 0.0) {
                volt = Math.abs(close / prevClose - 1.0);
            }
            s.avgVolatility = ewma(s.avgVolatility, volt, EWMA_ALPHA_SLOW);

            // Средний OI
            s.avgOiUsd = ewma(s.avgOiUsd, oiUsd, EWMA_ALPHA_SLOW);

            // Если где-то нужна "скорость/наклон" OI за бар — считаем локально:
            // double oiSlope = (prevOi != null ? (oiUsd - prevOi) / Math.max(prevOi, 1.0) : 0.0);
            // (Не сохраняем в состояние, если поле не предусмотрено.)

            // Сброс минутного «живого» потока агрессора — если у тебя это делается на закрытии минуты.
            s.buyAgg1m  = 0.0;
            s.sellAgg1m = 0.0;
        }
    }


    /** Тиковые сделки. buy == true => агрессор-покупатель (рыночная). usd — объём в USD. */
    public void onTrade(String symbol, boolean buy, double usd) {
        SymbolState s = state.computeIfAbsent(symbol, k -> new SymbolState());
        synchronized (s) {
            // «живой» поток за ~последнюю минуту (твоя логика)
            if (buy) s.buyAgg1m += usd; else s.sellAgg1m += usd;
            double tot = s.buyAgg1m + s.sellAgg1m;
            if (tot > 0) {
                s.avgDeltaBuy = ewma(s.avgDeltaBuy, s.buyAgg1m / tot, EWMA_ALPHA_FAST);
                s.avgFlowUsd  = ewma(s.avgFlowUsd,  tot,              EWMA_ALPHA_SLOW);
            }

            // ——— блок агрессора для всплесков ———
            s.aggressorDirections.add(buy);
            s.aggressorVolumes.add(usd);

            if (s.aggressorDirections.size() > 50) {
                s.aggressorDirections.remove(0);
                s.aggressorVolumes.remove(0);
            }

            double alpha = 0.2;
            s.avgAggressorVol = (s.avgAggressorVol == 0)
                    ? usd
                    : s.avgAggressorVol * (1 - alpha) + usd * alpha;
            // ——— конец блока агрессора ———
        }
    }

    /** Ликвидации — если используешь как «смарт-мани» сигнал. */
    public void onLiquidation(String symbol, boolean longSideWasLiquidated, double usd) {
        SymbolState s = state.computeIfAbsent(symbol, k -> new SymbolState());
        synchronized (s) {
            if (longSideWasLiquidated) {
                s.liqLongUsd = ewma(s.liqLongUsd, usd, EWMA_ALPHA_FAST);
            } else {
                s.liqShortUsd = ewma(s.liqShortUsd, usd, EWMA_ALPHA_FAST);
            }
        }
    }

    /* =========================
     *         ANALYZE
     * ========================= */

    public Optional<signal.TradeSignal> analyze(String symbol) {
        SymbolState s = state.get(symbol);
        if (s == null) return Optional.empty();

        synchronized (s) {
            // «прогрев»
            if (s.closes.size() < MIN_BARS_FOR_ANALYSIS) return Optional.empty();
            if (System.currentTimeMillis() - startTime < 60_000) return Optional.empty();

            // ——— Диагностика импульса (по желанию) ———
            // LiquidityImpulseChecker.debugCheck(symbol, s);

            // 1) фильтр ускорения OI (для микро включён, для крупных по желанию)
//            if (!OIAccelerationFilter.pass(s)) {
//                System.out.println("[Filter] Signal rejected by OIAccelerationFilter");
//                return Optional.empty();
//            }

            // 2) Ликвидность монеты
            boolean isHeavy = Settings.SEED_HEAVY.contains(symbol) || s.avgVolUsd >= 5_000_000;
            double  minOi   = isHeavy ? MIN_OI_HEAVY : MIN_OI_LIGHT;

            double oiNow = lastOr0(s.oiList);
            if (oiNow < minOi) {
                DebugPrinter.printIgnore(symbol, "Low OI: " + (long) oiNow);
                return Optional.empty();
            }

            // 3) «живой» поток
            double flow = s.buyAgg1m + s.sellAgg1m;
            DebugPrinter.monitor(symbol, s, 0.0);

            double minFlow = Math.max(MIN_FLOW_FLOOR, s.avgVolUsd * MIN_FLOW_RATIO);
            if (flow < minFlow) {
                DebugPrinter.printIgnore(symbol, "Low flow: " + (long) flow);
                return Optional.empty();
            }

            // 4) нормированные метрики
            double volNow = Math.max(lastOr0(s.volumes), 0.0);
            double volRel = safeRatio(volNow, s.avgVolUsd);
            double oiRel  = safeRatio(oiNow, s.avgOiUsd);

            double buyRatio = (flow > 0) ? (s.buyAgg1m / flow) : 0.5;
            double deltaShift = Math.max(0, buyRatio - s.avgDeltaBuy);

            double currVolt = volatility(s);
            double voltRel  = safeRatio(currVolt, s.avgVolatility);

            double longScore =
                    W_VOL   * cap(volRel) +
                            W_OI    * cap(oiRel)  +
                            W_DELTA * cap(deltaShift / 0.10) +
                            W_VOLT  * cap(voltRel / 2.0);

            String bias = (longScore >= 0.5) ? "LONG" : "SHORT";

            // 5) Адаптивный «агрессор-всплеск» (онлайн-пороги из AutoTuner)
//            boolean isLong = longScore >= 0.5;
//            if (!AdaptiveAggressorFilter.pass(s, isLong, "GLOBAL")) {
//                DebugPrinter.printIgnore(symbol, "[Filter] AdaptiveAggr");
//                return Optional.empty();
//            }

            double score = longScore;
            DebugPrinter.monitor(symbol, s, score);

            // 6) сила
            signal.SignalStrength strength = (score < 0.40) ? signal.SignalStrength.WEAK :
                    (score < 0.60) ? signal.SignalStrength.MEDIUM : signal.SignalStrength.STRONG;

            double base   = isHeavy ? BASE_THRESHOLD_HEAVY : BASE_THRESHOLD_LIGHT;
            double watchT = base;
            double enterT = watchT * ENTER_MULTIPLIER;

            long now = System.currentTimeMillis();
            if (now < s.cooldownUntil) return Optional.empty();
            if (now - s.lastSignalAtMs < MIN_SIGNAL_GAP_MS) return Optional.empty();

            // 7) Предфильтр (минимальные условия)
            double MIN_STRONG_SCORE = 0.55;
            double MIN_STRONG_FLOW  = 60_000;
            double MIN_BUY_POWER    = 0.52;
            double MIN_OI_IMPULSE   = minOi * 1.01;

            if (score < MIN_STRONG_SCORE || flow < MIN_STRONG_FLOW || buyRatio < MIN_BUY_POWER || oiNow < MIN_OI_IMPULSE) {
                DebugPrinter.printIgnore(symbol, String.format(
                        "Weak signal | score=%.2f flow=%.0f buy=%.2f OI=%.0f",
                        score, flow, buyRatio, oiNow
                ));
                return Optional.empty();
            }

            // 8) Smart-Money согласование
            double smAlign = smartMoneyAlignment(bias, s, oiRel);
            if (MICRO_NN_ENABLED && oiNow < MICRO_OI_USD) {
                if (smAlign < SM_FILTER_FOR_MICRO) {
                    DebugPrinter.printIgnore(symbol, String.format("SM align low (micro): %.2f", smAlign));
                    return Optional.empty();
                }
            } else {
                if (smAlign < SM_MIN_ALIGN) {
                    DebugPrinter.printIgnore(symbol, String.format("SM align low: %.2f", smAlign));
                    return Optional.empty();
                }
            }
            score *= (1.0 + SM_BONUS * clamp01(smAlign));

            // 9) Персистентность и выход
            if (score >= watchT) {
                s.watchStreak++;
                if (score >= enterT) s.enterStreak++;

                if (s.enterStreak >= ENTER_PERSISTENCE) {

                    boolean isMicro = oiNow < Settings.MICRO_OI_USD;

                    // MicroNN: только для микро
                    if (MICRO_NN_ENABLED && isMicro) {
                        double p = MicroNN.predict(s, true);
                        if (p < MICRO_NN_THRESHOLD) {
                            DebugPrinter.printIgnore(symbol, String.format("MicroNN reject p=%.2f", p));
                            reset(s);
                            return Optional.empty();
                        } else {
                            score *= (1.0 + 0.10 * (p - MICRO_NN_THRESHOLD));
                        }
                    }

                    signal.TradeSignal enter = new signal.TradeSignal(
                            symbol,
                            signal.Stage.ENTER,
                            bias,
                            s.lastPrice,
                            round2(score),
                            strength,
                            reason("ENTER", volRel, oiRel, buyRatio, voltRel),
                            oiNow, volNow, round2(buyRatio), round2(voltRel),
                            s.lastFunding,
                            isMicro
                    );

                    // LiquidityImpulseChecker.evaluate(enter, s);

                    // Статистика: первый снапшот — без SQL
                    stats.SignalSnapshot snap = new stats.SignalSnapshot(
                            System.currentTimeMillis(),
                            s.lastPrice,
                            oiNow,
                            volNow,
                            buyRatio,
                            voltRel,
                            s.lastFunding,
                            "initial",
                            0.0,  // peak profit at start
                            0.0   // drawdown at start
                    );
                    stats.SignalStatsService.getInstance().trackSignal(
                            symbol, "ENTER", bias, s.lastPrice, score, isMicro, snap
                    );

                    s.cooldownUntil = now + (isHeavy ? COOLDOWN_MS_HEAVY : COOLDOWN_MS_LIGHT);
                    s.lastSignalAtMs = now;
                    reset(s);
                    return Optional.of(enter);
                }
            } else {
                reset(s);
            }

            DebugPrinter.tryPrint(symbol, s, score);
            return Optional.empty();
        }
    }

    /* =========================
     *      HELPER METHODS
     * ========================= */

    private static double ewma(double prev, double x, double alpha) {
        if (prev == 0) return x;
        return prev * (1 - alpha) + x * alpha;
    }

    private static double lastOr0(Deque<Double> dq) {
        return (dq == null || dq.isEmpty()) ? 0.0 : dq.getLast();
    }

    private static double safeRatio(double a, double b) {
        return (b > 0) ? (a / b) : 1.0;
    }

    private static double cap(double x) {
        if (x < 0) return 0;
        if (x > 3) return 3;
        return x;
    }

    private static double clamp01(double x) {
        return Math.max(0.0, Math.min(1.0, x));
    }

    private static double volatility(state.SymbolState s) {
        if (s.closes.size() < 2) return 0.0;
        Double[] arr = s.closes.toArray(new Double[0]);
        double a = arr[arr.length - 1];
        double b = arr[arr.length - 2];
        return (b > 0) ? Math.abs(a / b - 1.0) : 0.0;
    }

    private static String reason(String tag, double volRel, double oiRel, double buyRatio, double voltRel) {
        return String.format("[%s] vol×=%.2f oi×=%.3f buy=%.2f volt×=%.2f",
                tag, volRel, oiRel, buyRatio, voltRel);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static void reset(SymbolState s) {
        s.watchStreak = 0;
        s.enterStreak = 0;
    }

    /** Прокс-оценка «смарт-мани»: ликвидации на встречной стороне + OI и funding. */
    private static double smartMoneyAlignment(String bias, state.SymbolState s, double oiRel) {
        // Нормализуем ликвидации: если LONG-идея — интересны ликвидации short-стороны
        double liqOpp = "LONG".equals(bias) ? s.liqShortUsd : s.liqLongUsd; // встречная сторона
        double liqScore = normalize(liqOpp, s.avgFlowUsd); // 0..1

        // Funding: для LONG приятнее <=0
        double fundScore = 0.5;
        if ("LONG".equals(bias)) {
            fundScore = s.lastFunding <= 0 ? 1.0 : Math.max(0.0, 1.0 - s.lastFunding * 10.0);
        } else {
            fundScore = s.lastFunding >= 0 ? 1.0 : Math.max(0.0, 1.0 + s.lastFunding * 10.0);
        }

        // OI: рост OI при импульсе — плюс
        double oiScore = Math.min(1.0, Math.max(0.0, (oiRel - 1.0) / 0.05)); // +5% => ~1.0

        // Весовая сумма
        return clamp01(SM_LIQ_W * liqScore + SM_OI_W * oiScore + SM_FUND_W * fundScore);
    }

    private static double normalize(double x, double ref) {
        if (ref <= 0) return 0.0;
        double r = x / (ref * 2.0); // мягкая нормализация
        return clamp01(r);
    }
}
