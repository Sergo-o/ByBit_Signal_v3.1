package filters;

import app.Settings;
import log.FilterLog;
import state.MarketSnapshot;
import state.SymbolState;

import java.util.Deque;

public final class PriceReversalFilter implements BaseFilter {

    // Пороговые значения, которые хорошо легли на всю историю сигналов.
    // При желании можно вынести их в Settings, но пока оставлю здесь,
    // чтобы было проще править и тестировать.
    private static final double OI_WEAK_LONG  = 0.998; // LONG: OI немного ослаб
    private static final double OI_WEAK_SHORT = 1.003; // SHORT: OI хотя бы не падает
    private static final double BR_REV_LONG   = 0.52;  // ниже этого — уже не "агрессивный лонг"
    private static final double BR_REV_SHORT  = 0.60;  // выше этого — покупатели явно ожили

    @Override
    public boolean pass(String symbol, SymbolState s, MarketSnapshot m) {
        // Базовый интерфейс фильтров: НИЧЕГО НЕ БЛОКИРУЕМ.
        // Фильтр разворота — только индикатор для логов / ReversalWatchService.
        isReversal(symbol, s, m);
        return true;
    }

    /**
     * Основная логика детектора разворота.
     *
     * Возвращает true, если:
     *  - был импульс (рост или падение) не меньше Settings.REV_MIN_SWING;
     *  - после него произошёл откат не меньше Settings.REV_MIN_PULLBACK;
     *  - и при этом OI/агрессор подтверждают ослабление тренда.
     *
     * Направление (LONG/SHORT) здесь НЕ учитывается: его знает ReversalWatchService.
     */
    public boolean isReversal(String symbol, SymbolState s, MarketSnapshot m) {
        int win = Settings.REV_WINDOW_BARS;   // рекомендую 3
        Deque<Double> closesDq = s.closes;
        if (closesDq == null || closesDq.size() < win) {
            return false;
        }

        double[] prices = lastN(closesDq, win);
        if (prices.length < win) {
            return false;
        }

        double first = prices[0];
        double last  = prices[prices.length - 1];

        if (first <= 0.0) {
            return false;
        }

        double max = max(prices);
        double min = min(prices);

        // Импульс вверх / вниз от начала окна
        double swingUp   = (max - first) / first;
        double swingDown = (first - min) / first;

        double minSwing    = Settings.REV_MIN_SWING;      // рекомендую 0.005 (0.5%)
        double minPullback = Settings.REV_MIN_PULLBACK;   // рекомендую 0.0025 (0.25%)

        boolean upImpulse   = swingUp   >= minSwing;
        boolean downImpulse = swingDown >= minSwing;

        // Насколько текущая цена откатила от экстремума
        double pullFromHigh = (last - max) / max; // < 0 если ушли ниже максимума
        double pullFromLow  = (last - min) / min; // > 0 если ушли выше минимума

        boolean pullbackAfterUp =
                upImpulse &&
                        max > last &&                       // последний close ниже локального max
                        Math.abs(pullFromHigh) >= minPullback;

        boolean pullbackAfterDown =
                downImpulse &&
                        min < last &&                       // последний close выше локального min
                        pullFromLow >= minPullback;

        // Если ни один сценарий (после импульса пошёл откат) не выполняется — разворота нет
        if (!pullbackAfterUp && !pullbackAfterDown) {
            return false;
        }

        // ----- Поток и OI -----
        double oiRel   = m.oiRel();     // относительный OI (вокруг 1.0)
        double br      = m.buyRatio();  // агрессор: доля покупателя

        // LONG-сценарий: был ап-тренд, пошёл откат
        // - OI ослаб (oiRel <= 0.998), ИЛИ
        // - агрессор ушёл из "жёсткого лонга" в нейтраль/ниже (br < 0.52)
        boolean longScenario =
                pullbackAfterUp &&
                        (
                                oiRel <= OI_WEAK_LONG ||
                                        br < BR_REV_LONG
                        );

        // SHORT-сценарий: был даун-тренд, пошёл откат
        // - OI хотя бы не падает (oiRel >= 1.0), ИЛИ
        // - агрессор заметно смещается в сторону покупателей (br > 0.52)
        boolean shortScenario =
                pullbackAfterDown &&
                        (
                                oiRel >= OI_WEAK_SHORT &&
                                        br > BR_REV_SHORT
                        );

        boolean reversal = longScenario || shortScenario;

        if (reversal) {
            FilterLog.log("REV", symbol, String.format(
                    "reversal candidate: price=%.6f swingUp=%.2f%% swingDown=%.2f%% " +
                            "pullFromHigh=%.2f%% pullFromLow=%.2f%% oiRel=%.4f br=%.3f",
                    last,
                    swingUp * 100.0,
                    swingDown * 100.0,
                    pullFromHigh * 100.0,
                    pullFromLow * 100.0,
                    oiRel,
                    br
            ));
        }

        return reversal;
    }

    /**
     * Берёт последние n значений из Deque в хронологическом порядке (старые → новые).
     */
    private static double[] lastN(Deque<Double> dq, int n) {
        int size = dq.size();
        int take = Math.min(n, size);

        double[] out = new double[take];
        // Преобразуем в массив, чтобы было удобно брать хвост
        Double[] arr = dq.toArray(new Double[0]);
        int start = size - take;

        for (int i = 0; i < take; i++) {
            out[i] = arr[start + i];
        }
        return out;
    }

    private static double max(double[] arr) {
        double m = Double.NEGATIVE_INFINITY;
        for (double v : arr) {
            if (v > m) m = v;
        }
        return m;
    }

    private static double min(double[] arr) {
        double m = Double.POSITIVE_INFINITY;
        for (double v : arr) {
            if (v < m) m = v;
        }
        return m;
    }
}
