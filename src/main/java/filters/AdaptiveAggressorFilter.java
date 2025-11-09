package filters;

import state.SymbolState;
import tuning.AutoTuner;

public final class AdaptiveAggressorFilter {

    private AdaptiveAggressorFilter() {}

    public static boolean pass(SymbolState s, boolean isLong, String symbol) {
        if (s == null || s.aggressorVolumes == null || s.aggressorDirections == null) return false;
        if (s.aggressorVolumes.size() < 5 || s.aggressorDirections.size() < 5) return false;

        boolean isMicro = s.avgOiUsd > 0 && s.avgOiUsd < 5_000_000;

        // берём профиль из тюнера (если нет — тюнер вернёт дефолт)
        var params = AutoTuner.getInstance().getParams(isMicro ? AutoTuner.Profile.MICRO : AutoTuner.Profile.GLOBAL);

        final double vol1m   = s.avgVolatility; // «напряжение» рынка
        final double avgTick = (s.avgAggressorVol > 0) ? s.avgAggressorVol : 1.0;

        int    minStreak = (vol1m > 0.012 ? params.minStreak + 1 : params.minStreak);
        double spikeX    = params.minVolumeSpikeX * (vol1m > 0.012 ? 1.10 : 1.00) * (isMicro ? 0.95 : 1.00);
        double domMin    = params.minDominance    * (vol1m > 0.012 ? 1.05 : 1.00) * (isMicro ? 0.95 : 1.00);
        double absMin    = Math.max(params.minAbsVolumeUsd * (isMicro ? 0.7 : 1.0), avgTick * 1.05);

        // работаем с массивами, чтобы иметь индекс доступа с конца
        Double[] vols = s.aggressorVolumes.toArray(new Double[0]);
        Boolean[] dirs = s.aggressorDirections.toArray(new Boolean[0]);

        int streak = 0;
        for (int i = vols.length - 1; i >= 0 && streak < minStreak; i--) {
            boolean dirIsBuy = dirs[i] != null && dirs[i];
            if (dirIsBuy != isLong) break;

            double v = vols[i] != null ? vols[i] : 0.0;
            if (v < absMin) break;
            if (avgTick > 0 && (v / avgTick) < spikeX) break;

            streak++;
        }
        if (streak < minStreak) return false;

        double flow = s.buyAgg1m + s.sellAgg1m;
        double buyRatio = (flow > 0) ? (s.buyAgg1m / flow) : 0.5;
        double dominance = isLong ? buyRatio : (1.0 - buyRatio);
        if (dominance < domMin) return false;

        AutoTuner.getInstance().onFilterPass(isMicro ? AutoTuner.Profile.MICRO : AutoTuner.Profile.GLOBAL);
        return true;
    }
}
