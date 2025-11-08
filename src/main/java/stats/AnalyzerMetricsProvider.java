package stats;

import core.PumpLiquidityAnalyzer;
import state.SymbolState;

public class AnalyzerMetricsProvider implements SignalStatsService.ICurrentMetricsProvider {

    private final PumpLiquidityAnalyzer analyzer;

    public AnalyzerMetricsProvider(PumpLiquidityAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    @Override
    public SignalStatsService.CurrentMetrics getMetricsFor(String symbol) {
        SymbolState s = analyzer.getSymbolState(symbol);
        if (s == null || s.closes.isEmpty()) return null;

        synchronized (s) {
            double price = s.lastPrice;
            double oiNow = (s.oiList.peekLast() != null) ? s.oiList.peekLast() : 0.0;
            double volNow = (s.volumes.peekLast() != null) ? s.volumes.peekLast() : 0.0;

            double flow = s.buyAgg1m + s.sellAgg1m;
            double buyRatio = (flow > 0) ? s.buyAgg1m / flow : 0.5;

            double volt = 0.0;
            if (s.closes.size() >= 2) {
                var it = s.closes.descendingIterator();
                double last = it.next();
                double prev = it.next();
                volt = Math.abs(last / prev - 1.0);
            }
            double voltRel = (s.avgVolatility > 0) ? volt / s.avgVolatility : 1.0;

            return new SignalStatsService.CurrentMetrics(
                    price, oiNow, volNow, buyRatio, voltRel, s.lastFunding
            );

        }
    }
}
