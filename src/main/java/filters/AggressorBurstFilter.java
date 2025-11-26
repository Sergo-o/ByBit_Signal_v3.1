package filters;

import app.Settings;
import log.FilterLog;
import state.SymbolState;

import java.util.Iterator;

/**
 * Burst-фильтр по агрессору.
 *
 * Ищет "залп" сделок в сторону сигнала:
 *  - несколько последних тиков подряд в одну сторону (streak)
 *  - средний объём в стрике выше среднего тикового (spikeMul)
 *  - большая доля объёма в сторону сигнала среди последних сделок (domRatio)
 *
 * Для микро-монет требования чуть строже, чем для обычных.
 */
public final class AggressorBurstFilter {

    private AggressorBurstFilter() {}

    // Базовые пороги, дальше корректируем под micro / волатильность
    private static final int    BASE_MIN_STREAK     = 3;
    private static final double BASE_MIN_SPIKE_MUL  = 1.15;  // было 2.0 — смягчили
    private static final double BASE_MIN_DOMINANCE  = 0.60; // базовая доминанта

    // сколько последних тиков учитывать для domRatio
    private static final int DOM_WINDOW = 30;

    public static boolean pass(SymbolState s, boolean isLong, String symbol) {
        if (!Settings.BURST_FILTER_ENABLED) {
            return true;
        }

        if (s.aggressorDirections.isEmpty() || s.aggressorVolumes.isEmpty()) {
            return !Settings.BURST_TRAIN;
        }

        boolean wantBuy = isLong;

        double oiLast = s.oiList.isEmpty() ? 0.0 : s.oiList.getLast();
        boolean micro = oiLast > 0 && oiLast < Settings.MICRO_OI_USD;

        double avgTickVol = Math.max(1.0, s.avgAggressorVol);
        double volt       = Math.max(1e-9, s.avgVolatility);

        // ===== 1. Стрик последних тиков в сторону сигнала =====
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
                break;
            }
        }

        if (streak == 0 || streakTotalVol <= 0.0) {
            return Settings.BURST_TRAIN;
        }

        double spikeMul = streakTotalVol / (avgTickVol * streak);

        // ===== 2. Доминанта объёма в сторону сигнала за последние N тиков =====
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

        // ===== 3. Пороги под профиль монеты и волатильность =====

        // 3.1. Длина стрика: 3 для обычных, 4 для micro
        int minStreak = BASE_MIN_STREAK + (micro ? 1 : 0);

        // 3.2. Spike: 1.3 для обычных, ~1.6 для micro (до учёта волатильности)
        double minSpikeMul = BASE_MIN_SPIKE_MUL + (micro ? 0.3 : 0.0);

        // 3.3. Доминанта: 0.60 для обычных, 0.68 для micro
        double minDom = BASE_MIN_DOMINANCE + (micro ? 0.08 : 0.0);

        // 3.4. Влияние волатильности — мягкое
        double voltFactor = 1.0 + Math.min(volt * 3.0, 0.3); // максимум +30% к spike
        minSpikeMul *= voltFactor;

        minDom = Math.min(0.95, minDom * (1.0 + Math.min(volt * 1.0, 0.15)));

        // 3.5. Минимальный абсолютный объём залпа
        double minAbsVol;
        if (micro) {
            // для микриков — от 7.5k до 10k в зависимости от AGGR_MIN_USD
            minAbsVol = Math.max(4000.0, Settings.AGGR_MIN_USD * 0.4);
        } else {
            // для нормальных монет — от 15k до ~20k
            minAbsVol = Math.max(8000.0, Settings.AGGR_MIN_USD * 0.6);
        }

        boolean pass =
                streak >= minStreak &&
                        spikeMul >= minSpikeMul &&
                        domRatio >= minDom &&
                        streakTotalVol >= minAbsVol;

        if (!pass && Settings.OI_FILTER_LOG_ENABLED) {
            String msg = String.format(
                    "streak=%d (min=%d) spike=%.2f (min=%.2f) dom=%.2f (min=%.2f) vol=%.0f (min=%.0f) micro=%s volt=%.3f",
                    streak, minStreak,
                    spikeMul, minSpikeMul,
                    domRatio, minDom,
                    streakTotalVol, minAbsVol,
                    micro, volt
            );
//            FilterLog.logBurst(symbol, msg);
        }

        if (Settings.BURST_TRAIN) {
            // в тренировочном режиме фильтр не блокирует
            return true;
        }

        return pass;
    }
}
