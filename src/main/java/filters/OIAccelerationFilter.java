package filters;

import app.Settings;
import state.SymbolState;

public final class OIAccelerationFilter {

    // Базовые пороги (минимальные; ты уже сильно их снижал — тут адекватный дефолт)
    private static final double BASE_MIN_OI_VELOCITY = 0.00010;  // 0.01%
    private static final double BASE_MIN_OI_ACCEL     = 0.00005;  // 0.005%

    public static boolean pass(SymbolState s) {
        // Нужны хотя бы 3 точки OI
        if (s.oiList == null || s.oiList.size() < 3) return true;

        // Достаём три последних значения безопасно
        Double[] arr = s.oiList.toArray(new Double[0]);
        double oi0 = nz(arr[arr.length - 1]);
        double oi1 = nz(arr[arr.length - 2]);
        double oi2 = nz(arr[arr.length - 3]);

        if (oi1 <= 0 || oi2 <= 0) return true;

        double velPrev = (oi1 - oi2) / oi2;
        double velNow  = (oi0 - oi1) / oi1;
        double accel   = velNow - velPrev;

        // микропрофиль — смягчаем пороги
        boolean isMicro = s.avgOiUsd > 0 && oi0 < Math.max(5_000_000, s.avgOiUsd * 0.75);

        double velThresh   = BASE_MIN_OI_VELOCITY;
        double accelThresh = BASE_MIN_OI_ACCEL;

        if (isMicro) {
            velThresh   *= 0.7;
            accelThresh *= 0.7;
        }
        // training mode — ещё мягче и подробный лог
        if (Settings.OI_TRAINING_MODE) {
            velThresh   *= 0.5;
            accelThresh *= 0.5;
        }

        boolean ok = velNow >= velThresh && accel >= accelThresh;

        if (!ok) {
            System.out.printf(
                    "[Filter] OIAcceler vel=%.5f (min=%.5f) accel=%.5f (min=%.5f) micro=%s oi: %.0f→%.0f%n",
                    velNow, velThresh, accel, accelThresh, isMicro, oi1, oi0
            );
        }
        return ok;
    }

    private static double nz(Double d) { return d == null ? 0.0 : d; }
}
