package filters;

import app.Settings;
import state.SymbolState;

/**
 * OIAccelerationFilter с мягким режимом обучения:
 * - В LIVE-режиме фильтрует по скорости/ускорению OI с учётом волатильности и микро-профиля.
 * - В TRAIN-режиме всегда пропускает (return true), но печатает диагностическую строку (как у тебя в логах).
 *
 * НИ ОДНО ПОЛЕ В SymbolState НЕ ТРЕБУЕТСЯ ДОБАВЛЯТЬ.
 */
public final class OIAccelerationFilter {

    // базовые пороги (мягкие; ты всегда можешь подкрутить их в Settings при желании)
    private static final double BASE_MIN_OI_VELOCITY = 0.00020;  // 0.020%
    private static final double BASE_MIN_OI_ACCEL    = 0.00010;  // 0.010%

    private OIAccelerationFilter() {}

    public static boolean pass(SymbolState s) {
        // нужно хотя бы 3 точки OI
        if (s.oiList == null || s.oiList.size() < 3) {
            return true;
        }

        // последние 3 значения (без добавления полей в SymbolState)
        final int n = s.oiList.size();
        Double oi0 = s.oiList.peekLast(); // текущее
        Double oi1 = s.oiList.toArray(new Double[0])[n - 2];
        Double oi2 = s.oiList.toArray(new Double[0])[n - 3];
        if (oi0 == null || oi1 == null || oi2 == null) {
            return true;
        }

        // скорость и ускорение OI
        double velPrev = (oi1 - oi2) / Math.max(Math.abs(oi2), 1.0);
        double velNow  = (oi0 - oi1) / Math.max(Math.abs(oi1), 1.0);
        double accel   = velNow - velPrev;

        // относительный всплеск объёма (если очередь пустая — volRel = 0)
        double volNow = (s.volumes != null && s.volumes.peekLast() != null) ? s.volumes.peekLast() : 0.0;
        double volRel = (s.avgVolUsd > 0.0) ? (volNow / s.avgVolUsd) : 0.0;

        // микро-профиль (как ты используешь повсюду)
        boolean isMicro = (s.avgOiUsd > 0.0) && (s.avgOiUsd < Settings.MICRO_OI_USD);

        // адаптация порогов: выше волатильность → пороги мягче
        double volt = s.avgVolatility; // сглажённая волатильность/бар в твоём состоянии
        double voltAdj = (volt > 0.0) ? clamp(1.0 / (1.0 + 30.0 * volt), 0.5, 1.0) : 1.0;

        // микро-коэффициент: для микро чуть мягче
        double microAdj = isMicro ? 0.8 : 1.0;

        // итого динамические пороги
        double minVel   = BASE_MIN_OI_VELOCITY * voltAdj * microAdj;
        double minAccel = BASE_MIN_OI_ACCEL    * voltAdj * microAdj;

        // TRAIN режим — всегда пропускаем, но печатаем диагностическую строку:
        if (Settings.OI_TRAINING_MODE) {
            System.out.printf("[Filter] OIAcceler vel=%.5f (min=%.5f) accel=%.5f (min=%.5f) volRel=%.2f micro=%s oi: %.0f→%.0f%n",
                    velNow, minVel, accel, minAccel, volRel, isMicro, oi1, oi0);
            return true;
        }

        // LIVE режим — строгая проверка:
        boolean pass = (velNow >= minVel) && (accel >= minAccel);

        if (!pass) {
            System.out.printf("[Filter] OIAcceler vel=%.5f (min=%.5f) accel=%.5f (min=%.5f) volRel=%.2f micro=%s oi: %.0f→%.0f%n",
                    velNow, minVel, accel, minAccel, volRel, isMicro, oi1, oi0);
        }
        return pass;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
