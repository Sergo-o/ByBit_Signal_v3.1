package filters;

import app.Settings;
import state.SymbolState;
import tuning.AutoTuner;

public final class AdaptiveAggressorFilter {

    private AdaptiveAggressorFilter() {}

    // Базовые пороги
    private static final double BASE_MIN_RATIO = 0.62;     // min dominace (62% покупок/продаж)
    private static final double BASE_MIN_FLOW_MUL = 0.25;  // min flow vs avgVolume

    public static boolean pass(SymbolState s, boolean isLong, String symbol) {

        double buy = s.buyAgg1m;
        double sell = s.sellAgg1m;
        double flow = buy + sell;
        if (flow <= 0) return true; // нет данных — пропускаем

        double ratio = buy / flow;
        if (!isLong) ratio = sell / flow; // если SHORT — считаем долю продавцов

        double avgVol = Math.max(1.0, s.avgVolUsd);

        // === адаптивные пороги ===
        double volt = Math.max(1e-9, s.avgVolatility);
        boolean micro = s.avgOiUsd < Settings.MICRO_OI_USD;

        boolean SOFT = Settings.AGGRESSOR_SOFT_MODE; // мягкий режим из твоего Settings

        double kSoft = SOFT ? 0.7 : 1.0;
        double kMicro = micro ? 0.8 : 1.0;

        // базовые, но умножаем на волатильность
        double minRatio = BASE_MIN_RATIO * kSoft * kMicro * (1.0 + 0.4 * volt);
        double minFlow  = avgVol * BASE_MIN_FLOW_MUL * kSoft * kMicro;

        // Подмешиваем авто-тюнер агрессора (если включён)
        if (Settings.OI_AUTOTUNER_ENABLED) {
            AutoTuner.Profile profile =
                    micro ? AutoTuner.Profile.MICRO : AutoTuner.Profile.GLOBAL;
            AutoTuner.AggressorParams p = AutoTuner.getInstance().getParams(profile);

            // Минимальная доминанта не ниже того, что настрогал AutoTuner
            minRatio = Math.max(minRatio, p.minDominance);

            // Дополнительно требуем минимальный абсолютный USD-поток
            double minAbsFlow = p.minAbsVolumeUsd;
            if (flow < minAbsFlow) {
                return false;
            }
        }

        boolean pass = ratio >= minRatio && flow >= minFlow;

        if (!pass) {
//            System.out.printf(
//                    "[Filter] AdaptiveAggr ratio=%.2f (min=%.2f) flow=%.0f (min=%.0f) micro=%s volt=%.4f%n",
//                    ratio, minRatio, flow, minFlow, micro, volt
//            );
        }
        return pass;
    }
}
