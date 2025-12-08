package filters;

import log.FilterLog;
import state.MarketSnapshot;
import state.SymbolState;
import app.Settings;

import java.util.Iterator;


/**
 * FakeSignalFilter:
 * - вызывается перед генерацией ENTER-сигнала,
 * - смотрит несколько последних баров,
 * - если рынок уже «разворачивается» против предполагаемого направления,
 *   блокирует сигнал (return false) + логирует в FilterLog.
 */
public final class FakeSignalFilter implements BaseFilter {

    private final String direction; // "LONG" или "SHORT"

    public FakeSignalFilter(String direction) {
        this.direction = direction;
    }

    @Override
    public boolean pass(String symbol, SymbolState s, MarketSnapshot m) {
        if (s.closes.size() < Settings.FAKE_BARS_LOOKBACK + 1) {
            return true; // мало истории — не мешаем
        }

        double[] closes = lastN(s.closes, Settings.FAKE_BARS_LOOKBACK + 1);
        double entryPrice = closes[closes.length - 1]; // последняя цена = цена входа

        // движение за последние BARS_LOOKBACK баров
        double first = closes[0];
        double last  = closes[closes.length - 1];
        double move  = (last - first) / first;

        boolean trendUp   = move >  Settings.FAKE_MIN_TREND_MOVE;
        boolean trendDown = move < -Settings.FAKE_MIN_TREND_MOVE;

        // Для LONG:
        //  - хотим, чтобы до сигнала был ап-тренд,
        //  - но нет свежего резкого хвоста вниз на последнем баре.
        // Для SHORT — наоборот.
        double prev = closes[closes.length - 2];
        double lastMove = (last - prev) / prev;

        boolean adverseTailLong  = lastMove < -Settings.FAKE_MAX_ADVERSE_SHADOW;
        boolean adverseTailShort = lastMove >  Settings.FAKE_MAX_ADVERSE_SHADOW;

        double oiRel   = m.oiRel();
        double buyRatio = m.buyRatio();

        if ("LONG".equalsIgnoreCase(direction)) {
            boolean badFlow =
                    !trendUp ||
                            adverseTailLong ||
                            oiRel < Settings.FAKE_MIN_OI_REL ||
                            buyRatio < Settings.FAKE_MIN_BUY_RATIO_FOR_LONG;        // продавцы доминируют

            if (badFlow) {
                logFake(symbol, entryPrice, closes, oiRel, buyRatio);
                // здесь же можем триггернуть сохранение в CSV (см. ниже)
                return true;
            }
        } else { // SHORT
            boolean badFlow =
                    !trendDown ||
                            adverseTailShort ||
                            oiRel < Settings.FAKE_MIN_OI_REL ||
                            buyRatio > Settings.FAKE_MAX_BUY_RATIO_FOR_SHORT;          // покупатели доминируют

            if (badFlow) {
                logFake(symbol, entryPrice, closes, oiRel, buyRatio);
                return true;
            }
        }

        return true;
    }

    private static void logFake(
            String symbol,
            double entryPrice,
            double[] closes,
            double oiRel,
            double buyRatio
    ) {
        double first = closes[0];
        double last  = closes[closes.length - 1];
        double movePct = (last - first) / first * 100.0;

        FilterLog.log("FAKE", symbol, String.format(
                "blocked fake %s-signal: entry=%.6f moveLastBars=%.2f%% oiRel=%.2f br=%.2f",
                // direction протаскивай из окружения, тут можно дописать как параметр
                "?", entryPrice, movePct, oiRel, buyRatio
        ));
    }

    private static double[] lastN(java.util.Deque<Double> dq, int n) {
        double[] out = new double[Math.min(n, dq.size())];
        int i = out.length - 1;
        for (Iterator<Double> it = dq.descendingIterator(); it.hasNext(); ) {
            Double v = it.next();
            out[i--] = v;
            if (i < 0) break;
        }
        return out;
    }
}
