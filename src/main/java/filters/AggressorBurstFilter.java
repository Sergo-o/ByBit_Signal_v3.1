package filters;

import state.SymbolState;

public final class AggressorBurstFilter {

    public static boolean pass(SymbolState s, boolean isLong) {
        if (s.aggressorVolumes.isEmpty() || s.aggressorDirections.isEmpty()) {
            System.out.println("AggressorBurstFilter: No aggressor volumes or directions");
            return false;
        }

        final int MIN_STREAK = DynamicThresholds.MIN_STREAK;
        final double MIN_VOLUME_SPIKE_X = DynamicThresholds.MIN_VOLUME_SPIKE_X;
        final double MIN_DOMINANCE = DynamicThresholds.MIN_DOMINANCE;
        final double MIN_ABS_VOLUME = DynamicThresholds.MIN_ABS_VOLUME_USD;

        double total = 0.0;
        Boolean[] dirs = s.aggressorDirections.toArray(new Boolean[0]);
        Double[] vols = s.aggressorVolumes.toArray(new Double[0]);

        int size = vols.length;
        if (size < MIN_STREAK) {
            System.out.println("AggressorBurstFilter: Not enough streaks, size=" + size);
            return false;
        }

        int streak = 0;
        double avgVol = s.avgAggressorVol > 0 ? s.avgAggressorVol : 1.0;

        for (int i = size - 1; i >= 0 && streak < MIN_STREAK; i--) {
            boolean dir = dirs[i]; // true=buy, false=sell
            if (dir != isLong) break;

            double vol = vols[i];
            if (vol < MIN_ABS_VOLUME) {
                System.out.println("AggressorBurstFilter: Low volume, vol=" + vol);
                break;
            }

            if (vol / avgVol < MIN_VOLUME_SPIKE_X) {
                System.out.println("AggressorBurstFilter: No volume spike, vol=" + vol + " avgVol=" + avgVol);
                break;
            }

            streak++;
            total += vol;
        }

        if (streak < MIN_STREAK) {
            System.out.println("AggressorBurstFilter: Not enough streak, streak=" + streak);
            return false;
        }

        double flow = s.buyAgg1m + s.sellAgg1m;
        double liveBuyRatio = flow > 0 ? (s.buyAgg1m / flow) : 0.5;
        double dominance = isLong ? liveBuyRatio : (1.0 - liveBuyRatio);

        if (dominance < MIN_DOMINANCE) {
            System.out.println("AggressorBurstFilter: Low dominance, dominance=" + dominance);
            return false;
        }

        return true;
    }
}
