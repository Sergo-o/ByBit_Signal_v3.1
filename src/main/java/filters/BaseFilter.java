package filters;

import state.MarketSnapshot;
import state.SymbolState;

public interface BaseFilter {
    boolean pass(String symbol, SymbolState s, MarketSnapshot m);
}


