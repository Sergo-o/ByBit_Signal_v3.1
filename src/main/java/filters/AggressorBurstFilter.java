package filters;

import state.SymbolState;

public final class AggressorBurstFilter {

    public static boolean pass(SymbolState s, boolean isLong) {
        if (s == null || s.aggressorVolumes == null || s.aggressorDirections == null) return false;

        int    MIN_STREAK        = DynamicThresholds.MIN_STREAK;
        double MIN_VOLUME_SPIKE_X = DynamicThresholds.MIN_VOLUME_SPIKE_X;
        double MIN_DOMINANCE      = DynamicThresholds.MIN_DOMINANCE;
        double MIN_ABS_VOLUME     = DynamicThresholds.MIN_ABS_VOLUME_USD;

// мягкий режим (не меняем имена/сигнатуры, просто ослабляем внутри)
        if (app.Settings.AGGRESSOR_SOFT_MODE) {
            // -1 к требуемому стрику, на 15% ниже всплеск и доминация, на 20% ниже абсолютный объём
            MIN_STREAK = Math.max(1, MIN_STREAK - 1);
            MIN_VOLUME_SPIKE_X *= 0.85;
            MIN_DOMINANCE      *= 0.85;
            MIN_ABS_VOLUME     *= 0.80;
        }


        if (s.aggressorVolumes.size() < MIN_STREAK || s.aggressorDirections.size() < MIN_STREAK) return false;

        Double[] vols = s.aggressorVolumes.toArray(new Double[0]);
        Boolean[] dirs = s.aggressorDirections.toArray(new Boolean[0]);

        double avgTick = s.avgAggressorVol > 0 ? s.avgAggressorVol : 1.0;

        int streak = 0;
        for (int i = vols.length - 1; i >= 0 && streak < MIN_STREAK; i--) {
            boolean dir = dirs[i] != null && dirs[i];
            if (dir != isLong) break;

            double v = vols[i] != null ? vols[i] : 0.0;
            if (v < MIN_ABS_VOLUME) break;
            if (avgTick > 0 && (v / avgTick) < MIN_VOLUME_SPIKE_X) break;

            streak++;
        }
        if (streak < MIN_STREAK) return false;

        double flow = s.buyAgg1m + s.sellAgg1m;
        double buyRatio = (flow > 0) ? s.buyAgg1m / flow : 0.5;
        double dom = isLong ? buyRatio : (1.0 - buyRatio);

        return dom >= MIN_DOMINANCE;
    }
}
