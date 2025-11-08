package filters;

import state.SymbolState;
import tuning.AutoTuner;

/**
 * Адаптивный фильтр «всплеск агрессора» с подстройкой порогов по волатильности и микро-профилю.
 */
public final class AdaptiveAggressorFilter {

    private AdaptiveAggressorFilter() {}

    public static boolean pass(SymbolState s, boolean isLong, String symbol) {
        if (s == null || s.aggressorVolumes == null || s.aggressorDirections == null) return false;
        if (s.aggressorVolumes.size() < 5 || s.aggressorDirections.size() < 5) return false;

        // определяем микро или нет
        boolean isMicro = s.avgOiUsd < 5_000_000;

        // выбираем профиль тюнера
        AutoTuner.AggressorParams p = AutoTuner.getInstance().getParams(
                isMicro ? AutoTuner.Profile.MICRO : AutoTuner.Profile.GLOBAL
        );

        // текущие характеристики
        final double vol1m   = s.avgVolatility;
        final double avgTick = (s.avgAggressorVol > 0) ? s.avgAggressorVol : 1.0;

        // адаптивные пороги
        int    minStreak = (vol1m > 0.012 ? p.minStreak + 1 : p.minStreak);
        double spikeX    = p.minVolumeSpikeX * (vol1m > 0.012 ? 1.15 : 1.00) * (isMicro ? 0.95 : 1.00);
        double domMin    = p.minDominance    * (vol1m > 0.012 ? 1.05 : 1.00) * (isMicro ? 0.95 : 1.00);
        double absMin    = Math.max(p.minAbsVolumeUsd * (isMicro ? 0.7 : 1.0), avgTick * 1.1);

        // streak поиска

        Boolean[] dirs = s.aggressorDirections.toArray(new Boolean[0]);
        Double[] vols  = s.aggressorVolumes.toArray(new Double[0]);

        int size = dirs.length;
        int streak = 0;

        for (int i = size - 1; i >= 0 && streak < minStreak; i--) {
            boolean dirIsBuy = dirs[i];
            if (dirIsBuy != isLong) break;

            double vol = vols[i];
            if (vol < absMin) break;
            if (vol / avgTick < spikeX) break;

            streak++;
        }
        if (streak < minStreak) return false;

        // проверка доминации
        double flow = s.buyAgg1m + s.sellAgg1m;
        double liveBuyRatio = (flow > 0) ? (s.buyAgg1m / flow) : 0.5;
        double dominance = isLong ? liveBuyRatio : (1.0 - liveBuyRatio);
        if (dominance < domMin) return false;

        // фикс: передаем профиль при тюнинге
        AutoTuner.getInstance().onFilterPass(
                isMicro ? AutoTuner.Profile.MICRO : AutoTuner.Profile.GLOBAL
        );

        return true;
    }
}
