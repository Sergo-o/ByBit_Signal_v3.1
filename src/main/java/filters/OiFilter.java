package filters;

import state.MarketSnapshot;
import state.SymbolState;

public class OiFilter implements BaseFilter {
    private final double minOi;
    public OiFilter(double minOi) { this.minOi = minOi; }
    @Override public boolean pass(String symbol, SymbolState s, MarketSnapshot m) {
        return m.oiNow() >= minOi;
    }
}


