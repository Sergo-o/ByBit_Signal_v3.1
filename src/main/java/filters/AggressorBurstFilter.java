package filters;

import app.Settings;
import state.SymbolState;

import java.util.Iterator;

public final class AggressorBurstFilter {

    private AggressorBurstFilter() {}

    private static final int BASE_MIN_STREAK = 3;
    private static final double BASE_MIN_SPIKE_MUL = 2.2;
    private static final double BASE_MIN_DOMINANCE = 0.62;

    public static boolean pass(SymbolState s, boolean isLong) {
        if (s.aggressorVolumes.isEmpty() || s.aggressorDirections.isEmpty())
            return true; // нет данных — не блокируем

        int streak = 0;
        double total = 0;
        double avg = Math.max(1, s.avgAggressorVol);

        Iterator<Boolean> dirIt = s.aggressorDirections.descendingIterator();
        Iterator<Double> volIt = s.aggressorVolumes.descendingIterator();

        while (dirIt.hasNext() && volIt.hasNext()) {
            boolean dir = dirIt.next();
            double vol = volIt.next();

            if (dir != isLong) break;

            streak++;
            total += vol;

            if (streak >= BASE_MIN_STREAK) break;
        }
        double micro = s.avgOiUsd < Settings.MICRO_OI_USD ? 0.8 : 1.0;
        boolean SOFT = Settings.BURST_FILTER_ENABLED;

        double kSoft = SOFT ? 0.6 : 1.0;
        double volt = Math.max(1e-9, s.avgVolatility);

        double minStreak    = BASE_MIN_STREAK * kSoft;
        double minSpikeMul  = BASE_MIN_SPIKE_MUL * kSoft * micro * (1 + volt * 0.5);
        double minDominance = BASE_MIN_DOMINANCE * kSoft * micro;

        double dominance = avg > 0 ? (total / (avg * streak)) : 1.0;

        boolean pass = streak >= minStreak && dominance >= minSpikeMul;

        if (!pass) {
            System.out.printf(
                    "[Filter] Burst streak=%d (min=%.0f) dom=%.2f (min=%.2f) micro=%s volt=%.3f%n",
                    streak, minStreak, dominance, minSpikeMul, micro, volt
            );
        }

        return pass;
    }
}
