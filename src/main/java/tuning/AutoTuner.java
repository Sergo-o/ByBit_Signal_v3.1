package tuning;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory авто-тюнер.
 * Совместим с текущим кодом:
 *  - AggressorParams (как было)
 *  - + OIParams для OIAccelerationFilter
 *  - Профили GLOBAL / MICRO
 */
public final class AutoTuner {

    // --- профили ---
    public enum Profile { GLOBAL, MICRO }

    // --- агрессорные пороги (как у тебя было) ---
    public static final class AggressorParams {
        public int    minStreak        = 4;
        public double minVolumeSpikeX  = 2.2;
        public double minDominance     = 0.68;
        public double minAbsVolumeUsd  = 30_000;

        public double microOiUsd       = 5_000_000;

        // сглаживающие метрики
        public double ewmaWinRate      = 0.0;
        public double ewmaPeakProfit   = 0.0;
        public double ewmaDrawdown     = 0.0;
        public double ewmaThroughput   = 0.0;
    }

    // --- пороги для OIAccelerationFilter ---
    public static final class OIParams {
        // базовые пороги (в долях, не процентах)
        public double minVelBase    = 1e-6;
        public double minAccelBase  = 5e-7;

        // ослабление порогов при высоком volRel: 1 - clamp((volRel-1)*coef, 0..0.8)
        public double volRelaxCoef  = 0.25;

        // доп. ослабление для MICRO-профиля (множитель)
        public double microRelaxK   = 0.6;

        // сглаживающие метрики качества
        public double ewmaPeakProfit = 0.0;
        public double ewmaDrawdown   = 0.0;
    }

    private static final AutoTuner INSTANCE = new AutoTuner();
    public static AutoTuner getInstance() { return INSTANCE; }

    private final Map<Profile, AggressorParams> aggrProfiles = new ConcurrentHashMap<>();
    private final Map<Profile, OIParams>        oiProfiles   = new ConcurrentHashMap<>();

    private AutoTuner() {
        // --- Aggressor ---
        aggrProfiles.put(Profile.GLOBAL, new AggressorParams());
        aggrProfiles.put(Profile.MICRO,  new AggressorParams());

        // Ослабим MICRO немного дефолтно
        aggrProfiles.get(Profile.MICRO).minStreak       = 2;
        aggrProfiles.get(Profile.MICRO).minVolumeSpikeX = 1.6;
        aggrProfiles.get(Profile.MICRO).minDominance    = 0.60;
        aggrProfiles.get(Profile.MICRO).minAbsVolumeUsd = 15_000;

        // --- OI ---
        oiProfiles.put(Profile.GLOBAL, new OIParams());
        oiProfiles.put(Profile.MICRO,  new OIParams());
        // MICRO — мягче
        oiProfiles.get(Profile.MICRO).microRelaxK = 0.5;    // ещё чуть мягче для микро
    }

    // ===== Aggressor API (как было) =====
    public AggressorParams getParams(Profile profile) {
        return aggrProfiles.computeIfAbsent(profile, k -> new AggressorParams());
    }

    /** зови при успешном прохождении адаптивного фильтра агрессора */
    public void onFilterPass(Profile profile) {
        var p = getParams(profile);
        p.ewmaThroughput = ewma(p.ewmaThroughput, 1.0, 0.02);
    }

    /** зови по завершении сигнала (мы уже так делаем в коде) */
    public void onSignalFinished(Profile profile, double peakProfitPct, double drawdownPct) {
        // Aggressor: мягкая адаптация как раньше
        {
            var p = getParams(profile);
            p.ewmaPeakProfit = ewma(p.ewmaPeakProfit, peakProfitPct, 0.05);
            p.ewmaDrawdown   = ewma(p.ewmaDrawdown,   Math.abs(drawdownPct), 0.05);

            double quality = p.ewmaPeakProfit - p.ewmaDrawdown;
            if (quality > 1.0) {
                p.minVolumeSpikeX = clamp(p.minVolumeSpikeX * 0.99, 1.6, 3.5);
                p.minDominance    = clamp(p.minDominance - 0.005,   0.60, 0.85);
                p.minAbsVolumeUsd = clamp(p.minAbsVolumeUsd * 0.98, 10_000, 200_000);
            } else if (quality < 0.0) {
                p.minVolumeSpikeX = clamp(p.minVolumeSpikeX * 1.01, 1.6, 3.5);
                p.minDominance    = clamp(p.minDominance + 0.005,   0.60, 0.85);
                p.minAbsVolumeUsd = clamp(p.minAbsVolumeUsd * 1.02, 10_000, 200_000);
            }
        }

        // OI: мягкая адаптация базовых порогов
        {
            var q = getOiParams(profile);
            q.ewmaPeakProfit = ewma(q.ewmaPeakProfit, peakProfitPct, 0.05);
            q.ewmaDrawdown   = ewma(q.ewmaDrawdown,   Math.abs(drawdownPct), 0.05);

            double quality = q.ewmaPeakProfit - q.ewmaDrawdown;
            if (quality > 1.0) {
                // хорошие исходы → чуть смягчаем
                q.minVelBase   = clamp(q.minVelBase   * 0.98, 1e-8,  1e-3);
                q.minAccelBase = clamp(q.minAccelBase * 0.98, 5e-9,  1e-3);
                q.volRelaxCoef = clamp(q.volRelaxCoef + 0.01, 0.10,  0.80);
            } else if (quality < 0.0) {
                // плохие исходы → ужесточаем
                q.minVelBase   = clamp(q.minVelBase   * 1.02, 1e-8,  1e-3);
                q.minAccelBase = clamp(q.minAccelBase * 1.02, 5e-9,  1e-3);
                q.volRelaxCoef = clamp(q.volRelaxCoef - 0.01, 0.10,  0.80);
            }
        }
    }

    // ===== OI API =====
    public OIParams getOiParams(Profile profile) {
        return oiProfiles.computeIfAbsent(profile, k -> new OIParams());
    }

    // ===== utils =====
    private static double ewma(double prev, double x, double alpha) {
        if (prev == 0) return x;
        return prev * (1 - alpha) + x * alpha;
    }
    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
