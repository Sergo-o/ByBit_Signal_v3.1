package ml;

import app.Settings;
import state.SymbolState;

public final class MicroNN {

    private MicroNN() {}

    /**
     * Простая логистическая заготовка.
     * Без внешних зависимостей. Коэф-ты подобраны эвристически и мягко.
     * Возвращает p in [0..1] — вероятность "хорошего" прохода сигнала.
     */
    public static double predict(SymbolState s, boolean isLong) {
        double price = s.lastPrice;

        double volNow = (s.volumes.peekLast() != null) ? s.volumes.peekLast() : 0.0;
        double avgVol = Math.max(s.avgVolUsd, 1.0);
        double volX = volNow / avgVol;

        double oiNow = (s.oiList.peekLast() != null) ? s.oiList.peekLast() : 0.0;
        double avgOi = Math.max(s.avgOiUsd, 1.0);
        double oiX = oiNow / avgOi;

        double flow = s.buyAgg1m + s.sellAgg1m;
        double flowX = flow / Math.max(s.avgFlowUsd, 1.0);

        double buyRatio = (flow > 0) ? s.buyAgg1m / flow : 0.5;
        double deltaShift = buyRatio - s.avgDeltaBuy;

        double volt = 0.0;
        if (s.closes.size() >= 2) {
            var it = s.closes.descendingIterator();
            double last = it.next();
            double prev = it.next();
            volt = Math.abs(last / prev - 1.0);
        }
        double voltRel = (s.avgVolatility > 0) ? (volt / s.avgVolatility) : 1.0;

        // Фичи
        double x =
                + 0.80 * Math.log1p(Math.max(0, volX - 1.0))
                        + 0.90 * Math.log1p(Math.max(0, oiX  - 1.0))
                        + 1.10 * Math.max(0, deltaShift) / 0.10
                        + 0.60 * Math.log1p(Math.max(0, flowX - 1.0))
                        + 0.30 * Math.log1p(Math.max(0, voltRel/2.0));

        if (!isLong) x *= 0.9;

        // Лёгкая нормализация
        double p = 1.0 / (1.0 + Math.exp(-x));
        // Капнем
        if (p < 0) p = 0; else if (p > 1) p = 1;
        return p;
    }
}
