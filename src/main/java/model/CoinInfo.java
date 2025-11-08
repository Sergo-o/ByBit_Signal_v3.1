package model;

public class CoinInfo {
    public String symbol;
    public volatile double lastPrice = 0;
    public volatile double openInterest = 0;
    public volatile double fundingRate = 0;
    public long updatedAt = System.currentTimeMillis();
}

