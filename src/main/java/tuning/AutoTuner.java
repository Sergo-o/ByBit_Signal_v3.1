package tuning;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AutoTuner {

    /** Профиль настроек — обычный рынок и микро-монеты */
    public enum Profile { GLOBAL, MICRO }

    /** Пакет параметров для адаптации */
    public static class AggressorParams {
        public int    minStreak        = 4;
        public double minVolumeSpikeX  = 2.2;
        public double minDominance     = 0.68;
        public double minAbsVolumeUsd  = 30_000;

        /** Порог определения микро-монеты */
        public double microOiUsd       = 5_000_000;

        /** Порог для OI-импульса */
        public double minOiImpulseX    = 1.01;

        /** Адaptive volatility multiplier */
        public double volatilityBoost  = 1.0;

        /** EWMA-метрики качества сигналов */
        public double ewmaWinRate      = 0.0;
        public double ewmaPeakProfit   = 0.0;
        public double ewmaDrawdown     = 0.0;
        public double ewmaThroughput   = 0.0;
    }

    private static final AutoTuner INSTANCE = new AutoTuner();
    public static AutoTuner getInstance() { return INSTANCE; }

    private final Map<Profile, AggressorParams> profiles = new ConcurrentHashMap<>();

    private AutoTuner() {
        // Профиль для обычных инструментов
        profiles.put(Profile.GLOBAL, new AggressorParams());

        // Профиль для микро — немного слабее пороги
        profiles.put(Profile.MICRO, new AggressorParams() {{
            minStreak = 2;
            minVolumeSpikeX = 1.5;
            minDominance = 0.60;
            minAbsVolumeUsd = 15_000;
            minOiImpulseX = 1.005;
        }});
    }

    public AggressorParams getParams(Profile profile) {
        return profiles.computeIfAbsent(profile, k -> new AggressorParams());
    }

    /** Вызывается при факте прохождения фильтра (реальное tick-проявление силы) */
    public void onFilterPass(Profile profile) {
        AggressorParams p = getParams(profile);
        p.ewmaThroughput = ewma(p.ewmaThroughput, 1.0, 0.05);
    }

    /**
     * Вызывай когда трейд-сигнал ДОБИТ отслеживание
     * peakProfitPct / drawdownPct в % (0.4 = +0.4%)
     */
    public void onSignalFinished(Profile profile, double peakProfitPct, double drawdownPct) {
        AggressorParams p = getParams(profile);

        p.ewmaPeakProfit = ewma(p.ewmaPeakProfit, peakProfitPct, 0.05);
        p.ewmaDrawdown   = ewma(p.ewmaDrawdown,   Math.abs(drawdownPct), 0.05);

        double quality = p.ewmaPeakProfit - p.ewmaDrawdown;

        // ✅ прибыльна стратегия → ослабляем пороги (проходим больше сигналов)
        if (quality > 0.6) {
            p.minVolumeSpikeX = clamp(p.minVolumeSpikeX * 0.99, 1.3, 3.5);
            p.minDominance    = clamp(p.minDominance - 0.01, 0.50, 0.85);
            p.minAbsVolumeUsd = clamp(p.minAbsVolumeUsd * 0.97, 8_000, 200_000);
        }

        // ❌ слабое качество → поджимаем фильтр
        if (quality < 0.0) {
            p.minVolumeSpikeX = clamp(p.minVolumeSpikeX * 1.01, 1.3, 3.5);
            p.minDominance    = clamp(p.minDominance + 0.01, 0.50, 0.85);
            p.minAbsVolumeUsd = clamp(p.minAbsVolumeUsd * 1.02, 8_000, 200_000);
        }
    }

    // ===========================
    // Helpers
    // ===========================

    private static double ewma(double prev, double x, double alpha) {
        if (prev == 0) return x;
        return prev * (1 - alpha) + x * alpha;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
