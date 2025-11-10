package app;

import java.util.Set;

public final class Settings {

    private Settings() {}

    // Debug
    public static final Set<String> DEBUG_COINS =
            Set.of("BTCUSDT","ETHUSDT","SOLUSDT","PEPEUSDT","WIFUSDT");

    // Порог микро-кап (по OI)

    // ====== WATCH / ENTER (остаются для будущего задействования) ======
    public static final double WATCH_MIN_SCORE   = 0.30;

    public static final double ENTER_MIN_SCORE   = 0.55;

    // только для обучения — ослабляем фильтр
    public static double TRAIN_OI_VEL_FACTOR = 0.2;
    public static double TRAIN_OI_ACCEL_FACTOR = 0.2;

    public static final double PENALTY_ADAPTIVE = 0.15;
    public static final double PENALTY_BURST = 0.25;

    // мягкий режим для агрессор-фильтров (ослабление порогов)
    public static volatile boolean AGGRESSOR_SOFT_MODE = false;


    public static final double MICRO_OI_USD = 2_000_000; // Новый порог микро-кап
    public static final double WATCH_MIN_VOL_X = 1.05; // Новый порог для WATCH
    public static final double WATCH_MIN_OI_X = 1.002; // Новый порог для WATCH
    public static final double WATCH_MIN_DELTA = 0.50; // Новый порог для WATCH
    public static final double WATCH_MIN_VOLAT_X = 1.10; // Новый порог для WATCH

    public static final double ENTER_MIN_VOL_X = 1.50; // Новый порог для ENTER
    public static final double ENTER_MIN_OI_X = 1.005; // Новый порог для ENTER
    public static final double ENTER_MIN_DELTA = 0.70; // Без изменений
    public static final double ENTER_MIN_VOLAT_X = 1.60; // Новый порог для ENTER

    public static final double AGGR_FLOW_X = 1.20; // Новый порог для агрессора
    public static final double AGGR_DOM_BONUS = 0.08; // Новый порог для агрессора
    public static final double AGGR_MIN_USD = 30_000; // Новый порог для агрессора

    // Авто-тюнинг OI-фильтра включён/выключен
    public static boolean OI_AUTOTUNER_ENABLED = true;

    // Флаги включения/выключения фильтров
    public static volatile boolean OI_FILTER_ENABLED      = true;
    public static volatile boolean AGGR_FILTER_ENABLED    = true;  // AdaptiveAggressorFilter
    public static volatile boolean BURST_FILTER_ENABLED   = true;  // AggressorBurstFilter

    // Мягкий режим (TRAIN) по каждому фильтру
    public static volatile boolean OI_TRAIN      = false;
    public static volatile boolean AGGR_TRAIN    = false;
    public static volatile boolean BURST_TRAIN   = false;


    public static final double MIN_FLOW_FLOOR = 20_000; // Новый порог для flow
    public static final double MIN_FLOW_RATIO = 0.02; // Новый порог для flow

    public static final double REGIME_MIN_SLOPE = 0.002; // Новый порог для Market Regime
    public static final double REGIME_VOL_LOW_X = 1.10; // Новый порог для Market Regime

    // === OI filter tuning ===
    public static boolean OI_FILTER_LOG_ENABLED = true;   // логировать метрики (как в твоих логах)
    public static boolean OI_TRAINING_MODE      = true;   // тренировочный режим: фильтр не блокирует ничего

    // Базовые очень мягкие пороги (в долях, не процентах)
    public static double OI_MIN_VEL_BASE   = 1e-6;        // базовый минимум по скорости OI
    public static double OI_MIN_ACCEL_BASE = 5e-7;        // базовый минимум по ускорению OI

    // Чем выше относительный объём (volRel), тем сильнее ослабляем пороги
// итоговый множитель = 1 - clamp((volRel-1) * OI_VOL_RELAX_COEF, 0..0.8)
    public static double OI_VOL_RELAX_COEF = 0.25;

    // Для микро-профиля можно ещё немного ослабить пороги (умножается на microK)
    public static double OI_MICRO_RELAX_K  = 0.6;         // 0.6 = ослабить на 40%


    // Порог силы
    public static final double BASE_THRESHOLD_HEAVY = 0.30; // было 0.26
    public static final double BASE_THRESHOLD_LIGHT = 0.25; // было 0.22
    public static final double ENTER_MULTIPLIER     = 1.25; // было 1.22

    // Persistence
    public static final int WATCH_PERSISTENCE = 1;
    public static final int ENTER_PERSISTENCE = 2;

    // Flow
    public static final double MIN_FLOW_USD   = 5_000;

    // Тайминги
    public static final long MIN_SIGNAL_GAP_MS = 8_000;
    public static final long COOLDOWN_MS_HEAVY = 90_000;
    public static final long COOLDOWN_MS_LIGHT = 45_000;

    // Окна и усреднения
    public static final int WINDOW_MINUTES = 15;
    public static final int MIN_BARS_FOR_ANALYSIS = 5;

    public static final double EWMA_ALPHA_FAST = 0.2;
    public static final double EWMA_ALPHA_SLOW = 0.05;

    // Весовые коэф-ты baseline-скоринга
    public static final double W_VOL   = 0.30;
    public static final double W_OI    = 0.30;
    public static final double W_DELTA = 0.20;
    public static final double W_VOLT  = 0.20;

    // Минимальный OI по классам ликвидности
    public static final double MIN_OI_HEAVY  = 1_000_000;
    public static final double MIN_OI_LIGHT  = 200_000;

    public static final Set<String> SEED_HEAVY =
            Set.of("BTCUSDT","ETHUSDT","SOLUSDT","BNBUSDT","TONUSDT","XRPUSDT");

    // ===== Aggressor burst (всплеск рыночного агрессора) =====
    // Требования к всплеску потока и доминации агрессора для ENTER

    // ===== Smart Money weight (пассив–актив прокси) =====
    // score *= (1 + SM_BONUS * smScore[0..1])
    public static final double SM_BONUS     = 0.15;
    public static final double SM_LIQ_W     = 0.50; // ликвидации на встречной стороне
    public static final double SM_OI_W      = 0.30; // oiRel и его дельта
    public static final double SM_FUND_W    = 0.20; // funding: Long любит <=0

    // нормировки smart-money
    public static final double SM_MIN_ALIGN = 0.20; // ниже — не усиливаем (и можно отфильтровать микро)
    public static final double SM_FILTER_FOR_MICRO = 0.10; // для микро допускаем слабее порог

    // ===== Микро-модель (логистическая заготовка) =====
    public static final boolean MICRO_NN_ENABLED   = true;
    public static final double  MICRO_NN_THRESHOLD = 0.55; // p>=0.55 пропускаем

    // Микро OI-ускорение (использовалось ранее; оставим как базовый фильтр)
    public static final double MICRO_OI_MIN_REL   = 1.01;
    public static final double MICRO_OI_MIN_SLOPE = 0.0025; // +0.25% за бар
    // Окно анализа рыночного режима (кол-во баров)
    public static final int REGIME_WINDOW_BARS = 20;  // можно менять (20 баров ≈ 20 минут)

    // ====== Market Regime Detection ======

}
