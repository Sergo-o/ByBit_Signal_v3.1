package filters;

public final class DynamicThresholds {
    // Текущие рабочие пороги (могут меняться в рантайме)
    public static volatile int    MIN_STREAK          = 3;
    public static volatile double MIN_VOLUME_SPIKE_X  = 2.2;
    public static volatile double MIN_DOMINANCE       = 0.62;
    public static volatile double MIN_ABS_VOLUME_USD  = 30_000;

    // Базовые значения для быстрого возврата
    private static final int    BASE_MIN_STREAK         = 3;
    private static final double BASE_MIN_VOLUME_SPIKE_X = 2.2;
    private static final double BASE_MIN_DOMINANCE      = 0.62;
    private static final double BASE_MIN_ABS_VOL_USD    = 30_000;

    private DynamicThresholds() {}

    public static void restoreDefaults() {
        MIN_STREAK         = BASE_MIN_STREAK;
        MIN_VOLUME_SPIKE_X = BASE_MIN_VOLUME_SPIKE_X;
        MIN_DOMINANCE      = BASE_MIN_DOMINANCE;
        MIN_ABS_VOLUME_USD = BASE_MIN_ABS_VOL_USD;
    }

    /** Мягкие пороги для TRAIN-режима агрессоров */
    public static void softenForTrain() {
        MIN_STREAK         = Math.max(1, BASE_MIN_STREAK - 1);
        MIN_VOLUME_SPIKE_X = BASE_MIN_VOLUME_SPIKE_X * 0.85;
        MIN_DOMINANCE      = Math.max(0.50, BASE_MIN_DOMINANCE - 0.03);
        // MIN_ABS_VOLUME_USD оставим как есть — зависит от твоего флоу
    }
}
