package filters;

import app.Settings;
import log.FilterLog;
import state.MarketSnapshot;
import state.SymbolState;

import java.util.Deque;

public final class PriceReversalFilter implements BaseFilter {

    @Override
    public boolean pass(String symbol, SymbolState s, MarketSnapshot m) {
        // базовый интерфейс фильтров: ничего не блокируем
        isReversal(symbol, s, m);
        return true;
    }

    /**
     * Возвращает true, если по текущим барам и метрикам видим кандидат на разворот.
     * Пишет в FilterLog с тегом [REV].
     */
    public boolean isReversal(String symbol, SymbolState s, MarketSnapshot m) {
        int win = Settings.REV_WINDOW_BARS;
        Deque<Double> closesDq = s.closes;
        if (closesDq.size() < win) {
            return false; // мало баров
        }

        double[] closes = lastN(closesDq, win);
        double lastPrice = closes[closes.length - 1];

        double first = closes[0];
        double max   = max(closes);
        double min   = min(closes);

        double swingUp   = (max - first) / first;   // рост от начала до max
        double swingDown = (first - min) / first;   // падение от начала до min

        boolean upImpulse   = swingUp   >= Settings.REV_MIN_SWING;
        boolean downImpulse = swingDown >= Settings.REV_MIN_SWING;

        double pullFromHigh = (lastPrice - max) / max; // негативный, если откатились вниз от пика
        double pullFromLow  = (lastPrice - min) / min; // позитивный, если ушли вверх от дна

        boolean pullbackAfterUp =
                upImpulse &&
                        max > lastPrice &&
                        Math.abs(pullFromHigh) >= Settings.REV_MIN_PULLBACK;

        boolean pullbackAfterDown =
                downImpulse &&
                        min < lastPrice &&
                        pullFromLow >= Settings.REV_MIN_PULLBACK;

        if (!pullbackAfterUp && !pullbackAfterDown) {
            return false;
        }

        double oiRel    = m.oiRel();
        double buyRatio = m.buyRatio();

        boolean oiWeak = oiRel <= 1.0;

        boolean brRevertAfterUp =
                upImpulse &&
                        buyRatio < Settings.REV_BR_NEUTRAL_BUY &&
                        buyRatio > Settings.REV_BR_STRONG_SELL;

        boolean brRevertAfterDown =
                downImpulse &&
                        buyRatio > Settings.REV_BR_NEUTRAL_SELL &&
                        buyRatio < Settings.REV_BR_STRONG_BUY;

        boolean reversal =
                (pullbackAfterUp   && (oiWeak || brRevertAfterUp)) ||
                        (pullbackAfterDown && (oiWeak || brRevertAfterDown));

        if (reversal) {
            FilterLog.log("REV", symbol, String.format(
                    "reversal candidate: price=%.6f swingUp=%.2f%% swingDown=%.2f%% pullFromHigh=%.2f%% pullFromLow=%.2f%% oiRel=%.2f br=%.2f",
                    lastPrice,
                    swingUp * 100.0,
                    swingDown * 100.0,
                    pullFromHigh * 100.0,
                    pullFromLow * 100.0,
                    oiRel,
                    buyRatio
            ));
        }

        return reversal;
    }

    // ===== helpers =====

    private static double[] lastN(Deque<Double> dq, int n) {
        int size = dq.size();
        int take = Math.min(n, size);

        double[] out = new double[take];
        Double[] arr = dq.toArray(new Double[0]);

        int start = size - take;
        for (int i = 0; i < take; i++) {
            out[i] = arr[start + i];
        }
        return out;
    }

    private static double max(double[] arr) {
        double m = Double.NEGATIVE_INFINITY;
        for (double v : arr) if (v > m) m = v;
        return m;
    }

    private static double min(double[] arr) {
        double m = Double.POSITIVE_INFINITY;
        for (double v : arr) if (v < m) m = v;
        return m;
    }
}
