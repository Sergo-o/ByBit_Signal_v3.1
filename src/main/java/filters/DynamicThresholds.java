package filters;

/**
 * Единая точка, где лежат актуальные пороги для агрессор-фильтров.
 * AutoTuner будет сюда писать обновления.
 */
public final class DynamicThresholds {
    private DynamicThresholds() {}

    // помечаем volatile, чтобы изменения были видны без синхронизации
    public static volatile int    MIN_STREAK          = 4;
    public static volatile double MIN_VOLUME_SPIKE_X  = 2.2;
    public static volatile double MIN_DOMINANCE       = 0.68;
    public static volatile double MIN_ABS_VOLUME_USD  = 30_000.0;
}
