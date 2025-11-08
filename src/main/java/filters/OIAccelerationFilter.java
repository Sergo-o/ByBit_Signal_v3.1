package filters;

import state.SymbolState;

public final class OIAccelerationFilter {

    private static final double MIN_OI_VELOCITY = 0.0005; // 0.05%
    private static final double MIN_OI_ACCEL = 0.0002;    // 0.02%

    public static boolean pass(SymbolState s) {
        if (s.oiList.size() < 3) return true;

        // Быстрый доступ к последним значениям
        var it = s.oiList.descendingIterator();
        double oi0 = it.next(); // текущий
        double oi1 = it.next(); // 1 мин назад
        double oi2 = it.next(); // 2 мин назад

        // скорость изменения OI (импульс)
        double velPrev = (oi1 - oi2) / Math.max(oi2, 1.0);
        double velNow  = (oi0 - oi1) / Math.max(oi1, 1.0);

        // ускорение — разница скоростей
        double accel = velNow - velPrev;

        // если OI растёт стабильно и ускоряется — пропускаем
        return velNow >= MIN_OI_VELOCITY && accel >= MIN_OI_ACCEL;
    }
}
