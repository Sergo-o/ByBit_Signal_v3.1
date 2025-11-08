package filters;

import state.MarketSnapshot;
import state.SymbolState;

public class ScoreFilter implements BaseFilter {
    private final double minScore;
    private final double minBuyPower;
    public ScoreFilter(double minScore, double minBuyPower) {
        this.minScore = minScore; this.minBuyPower = minBuyPower;
    }
    @Override public boolean pass(String symbol, SymbolState s, MarketSnapshot m) {
        return m.score() >= minScore && m.buyRatio() >= minBuyPower;
    }
}


