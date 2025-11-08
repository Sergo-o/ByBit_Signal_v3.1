package filters;

import state.MarketSnapshot;
import state.SymbolState;

public class FlowFilter implements BaseFilter {
    private final double minFlow;
    public FlowFilter(double minFlow) { this.minFlow = minFlow; }
    @Override public boolean pass(String symbol, SymbolState s, MarketSnapshot m) {
        return m.flow() >= minFlow;
    }
}


