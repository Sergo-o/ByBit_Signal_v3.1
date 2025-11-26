package filters;

import app.Settings;
import log.FilterLog;
import state.SymbolState;
import tuning.AutoTuner;

/**
 * Адаптивный фильтр агрессора:
 * - проверяет силу одностороннего потока (buy/sell) через buyRatio
 * - требует минимальный абсолютный USD-поток
 * - учитывает профиль micro / non-micro
 * - умеет подмешивать параметры из AutoTuner
 */
public final class AdaptiveAggressorFilter {

    private AdaptiveAggressorFilter() {
    }

    // Базовый множитель порога "потока" от среднего объёма (если нужен)
    private static final double BASE_MIN_FLOW_MUL = 0.15;  // min flow vs avgVolume

    public static boolean pass(SymbolState s, boolean isLong, String symbol) {
        // 0. Быстрая проверка: фильтр вообще включён?
        if (!Settings.AGGR_FILTER_ENABLED) {
            return true;
        }

        // 1. Собираем агрессора за последнюю минуту
        double buy = s.buyAgg1m;
        double sell = s.sellAgg1m;
        double flow = buy + sell;

        if (flow <= 0) {
            return false;
        }

        // Доля направления в сторону сигнала
        double ratio = isLong ? (buy / flow) : (sell / flow);

        double avgVol = Math.max(1.0, s.avgVolUsd);     // защита от 0
        double avgOi = Math.max(1.0, s.avgOiUsd);
        double volt = Math.max(1e-9, s.avgVolatility);
        boolean micro = avgOi < Settings.MICRO_OI_USD;

        // 2. Базовые пороги по доминанте агрессора
        double minRatioBase;
        if (isLong) {
            minRatioBase = micro
                    ? Settings.AGGR_MIN_RATIO_LONG_MICRO
                    : Settings.AGGR_MIN_RATIO_LONG;
        } else {
            // Для шорта мы уже взяли ratio = sell/flow,
            // поэтому используем те же "LONG"-пороги, но для SHORT-профиля
            minRatioBase = micro
                    ? Settings.AGGR_MIN_RATIO_SHORT_MICRO
                    : Settings.AGGR_MIN_RATIO_SHORT;
        }

        // 3. Базовый минимальный абсолютный поток для агрессора (USD)
        //    Можно привязать как к константе, так и к среднему объёму
        double targetFlow = avgVol * BASE_MIN_FLOW_MUL;
        double minAbsFlow = Math.max(
                Settings.AGGR_MIN_USD,
                Math.min(targetFlow, Settings.AGGR_MAX_FLOW_USD)
        );

        // 4. Подмешиваем авто-тюнер (если включён)
        if (Settings.AUTOTUNER_ENABLED) {
            AutoTuner.Profile profile =
                    micro ? AutoTuner.Profile.MICRO : AutoTuner.Profile.GLOBAL;
            AutoTuner.AggressorParams p = AutoTuner.getInstance().getParams(profile);

            // Минимальная доминанта не ниже, чем подобрал AutoTuner
            minRatioBase = Math.max(minRatioBase, p.minDominance);

            // Минимальный абсолютный USD-поток тоже не ниже, чем от тюнера
            minAbsFlow = Math.max(minAbsFlow, p.minAbsVolumeUsd);
        }

        // 5. Влияние волатильности (по желанию, мягко)
        // Чем выше волатильность, тем больше хотим доминанту
        double kVolt = 1.0 + 0.20 * Math.min(volt, 2.0); // +до 40% к порогу
        double minRatio = Math.min(0.95, minRatioBase * kVolt);

        // 6. Итоговая проверка с "мягким" допуском по объёму
        //    6.1. "Жёсткий" проход: и доминанта, и объём ок
        boolean hardPass = (ratio >= minRatio) && (flow >= minAbsFlow);

        //    6.2. "Почти хватило" объёма, но направление очень сильное:
        //         flow >= 70% от требуемого, ratio выше порога + 0.05
        double flowRatio = (minAbsFlow > 0.0) ? (flow / minAbsFlow) : 0.0;
        double dirThresh = Math.min(0.99, minRatio + 0.05);
        boolean strongDir = (ratio >= dirThresh);
        boolean softFlow = (flowRatio >= 0.70);

        boolean pass = hardPass || (softFlow && strongDir);

        if (!pass && Settings.OI_FILTER_LOG_ENABLED) {
            String side = isLong ? "LONG" : "SHORT";
            String msg = String.format(
                    "%s flow=%.0f (min=%.0f) flowRatio=%.2f ratio=%.2f (min=%.2f, strong>=%.2f) micro=%s volt=%.3f",
                    side,
                    flow, minAbsFlow, flowRatio,
                    ratio, minRatio, dirThresh,
                    micro, volt
            );
//            FilterLog.logAggr(symbol, msg);
        }

        // В режиме TRAIN можно логировать, но не блокировать
        if (Settings.AGGR_TRAIN) {
            return true;
        }

        return pass;

    }
}