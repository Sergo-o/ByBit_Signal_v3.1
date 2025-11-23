package filters;

import app.Settings;
import state.SymbolState;

import java.util.Iterator;

/**
 * Burst-фильтр по агрессору.
 *
 * Ищет "залп" сделок в сторону сигнала:
 *  - несколько последних тиков подряд в одну сторону (streak)
 *  - средний объём в стрике значительно выше среднего тикового (spikeMul)
 *  - большая доля объёма в сторону сигнала среди последних сделок (domRatio)
 *
 * Для микро-монет требования строгие (нужен реально мощный залп),
 * для обычных — чуть мягче.
 */
public final class AggressorBurstFilter {

    private AggressorBurstFilter() {}

    // Базовые пороги, дальше корректируем под micro / волатильность
    private static final int    BASE_MIN_STREAK     = 3;
    private static final double BASE_MIN_SPIKE_MUL  = 2.0;  // средний объём стрика в X раз выше среднего
    private static final double BASE_MIN_DOMINANCE  = 0.60; // доля объёма в сторону сигнала

    // сколько последних тиков учитывать для domRatio
    private static final int DOM_WINDOW = 30;

    public static boolean pass(SymbolState s, boolean isLong, String symbol) {
        // Если фильтр вообще выключен — пропускаем всё
        if (!Settings.BURST_FILTER_ENABLED) {
            return true;
        }

        // Если нет истории по агрессору — нечего анализировать
        if (s.aggressorDirections.isEmpty() || s.aggressorVolumes.isEmpty()) {
            return !Settings.BURST_TRAIN; // в TRAIN режиме можем считать "проход" не критичным
        }

        // ===== 1. Определяем micro / основные характеристики =====
        double oiLast = s.oiList.isEmpty() ? 0.0 : s.oiList.getLast();
        boolean micro = oiLast > 0 && oiLast < Settings.MICRO_OI_USD;

        double avgTickVol = Math.max(1.0, s.avgAggressorVol);
        double volt       = Math.max(1e-9, s.avgVolatility);

        // ===== 2. Ищем стрик последних тиков в направлении сигнала =====
        boolean wantBuy = isLong;

        int streak = 0;
        double streakTotalVol = 0.0;

        Iterator<Boolean> dirIt = s.aggressorDirections.descendingIterator();
        Iterator<Double>  volIt = s.aggressorVolumes.descendingIterator();

        while (dirIt.hasNext() && volIt.hasNext()) {
            boolean isBuy = dirIt.next();
            double  vol   = volIt.next();

            if (isBuy == wantBuy) {
                streak++;
                streakTotalVol += vol;
            } else {
                // как только направление поменялось — стоп
                break;
            }
        }

        if (streak == 0 || streakTotalVol <= 0.0) {
            // никакого залпа в нужную сторону не видно
            return Settings.BURST_TRAIN; // в TRAIN можно не блокировать
        }

        // средний объём в стрике относительно среднего тикового
        double spikeMul = streakTotalVol / (avgTickVol * streak);

        // ===== 3. Считаем доминанту объёма в сторону сигнала за последние N тиков =====
        double dirVolSum = 0.0;
        double allVolSum = 0.0;

        dirIt = s.aggressorDirections.descendingIterator();
        volIt = s.aggressorVolumes.descendingIterator();

        int count = 0;
        while (dirIt.hasNext() && volIt.hasNext() && count < DOM_WINDOW) {
            boolean isBuy = dirIt.next();
            double  vol   = volIt.next();
            allVolSum += vol;
            if (isBuy == wantBuy) {
                dirVolSum += vol;
            }
            count++;
        }

        double domRatio = (allVolSum > 0.0) ? (dirVolSum / allVolSum) : 0.0;

        // ===== 4. Формируем пороги под профиль монеты и волатильность =====

        int minStreak = BASE_MIN_STREAK + (micro ? 1 : 0); // микрикам → на 1 тик длиннее

        double minSpikeMul = BASE_MIN_SPIKE_MUL + (micro ? 0.3 : 0.0); // ~2.3 для micro
        double minDom      = BASE_MIN_DOMINANCE + (micro ? 0.08 : 0.0); // ~0.68 для micro

        // если волатильность уже высокая — требуем чуть больше "чистоты" залпа
        double voltFactor = 1.0 + Math.min(volt * 5.0, 0.5); // максимум +50% к порогам
        minSpikeMul *= voltFactor;
        // доминанту поднимаем мягче
        minDom = Math.min(0.95, minDom * (1.0 + Math.min(volt * 2.0, 0.3)));

        // минимальный абсолютный объём залпа
        double minAbsVol = Settings.AGGR_MIN_USD;

        boolean pass =
                streak >= minStreak &&
                        spikeMul >= minSpikeMul &&
                        domRatio >= minDom &&
                        streakTotalVol >= minAbsVol;

        if (!pass && Settings.OI_FILTER_LOG_ENABLED) {
            System.out.printf(
                    "[Filter|BURST] %s %s streak=%d (min=%d) spike=%.2f (min=%.2f) dom=%.2f (min=%.2f) vol=%.0f (min=%.0f) micro=%s volt=%.3f%n",
                    symbol != null ? symbol : "?",
                    (isLong ? "LONG" : "SHORT"),
                    streak, minStreak,
                    spikeMul, minSpikeMul,
                    domRatio, minDom,
                    streakTotalVol, minAbsVol,
                    micro, volt
            );
        }

        // В TRAIN-режиме фильтр не должен блочить сигнал
        if (Settings.BURST_TRAIN) {
            return true;
        }

        return pass;
    }
}
