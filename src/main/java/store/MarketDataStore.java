package store;

import model.CoinInfo;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MarketDataStore {

    private static final Map<String, CoinInfo> coins = new ConcurrentHashMap<>();

    public static CoinInfo get(String symbol) {
        return coins.get(symbol);
    }

    public static void update(String symbol, double price, double oi, double funding) {
        coins.computeIfAbsent(symbol, k -> { CoinInfo c = new CoinInfo(); c.symbol = symbol; return c; });
        CoinInfo c = coins.get(symbol);
        c.lastPrice = price;
        c.openInterest = oi;
        c.fundingRate = funding;
        c.updatedAt = System.currentTimeMillis();
    }

    public static void updateOI(String symbol, double oi) {
        coins.computeIfAbsent(symbol, k -> { CoinInfo c = new CoinInfo(); c.symbol = symbol; return c; });
        CoinInfo c = coins.get(symbol);
        c.openInterest = oi;
        c.updatedAt = System.currentTimeMillis();
    }

    public static Set<String> allSymbols() {
        return coins.keySet();
    }
}

