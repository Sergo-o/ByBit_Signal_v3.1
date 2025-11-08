package state;

import java.util.ArrayDeque;
import java.util.Deque;

public class SymbolState {

    // === Price / volumes / OI series ===
    public final Deque<Double> closes = new ArrayDeque<>();
    public final Deque<Double> volumes = new ArrayDeque<>();
    public final Deque<Double> oiList = new ArrayDeque<>();

    // === Aggressor streams (last N trades) ===
    public final Deque<Boolean> aggressorDirections = new ArrayDeque<>();
    public final Deque<Double> aggressorVolumes = new ArrayDeque<>();

    // === One-minute live flows ===
    public double buyAgg1m = 0.0;
    public double sellAgg1m = 0.0;

    public double liqBuy1m  = 0.0; // сумма ликвидаций шортов (buy pressure)
    public double liqSell1m = 0.0; // сумма ликвидаций лонгов (sell pressure)


    // === Liquidation EWMA ===
    public double liqLongUsd = 0.0;
    public double liqShortUsd = 0.0;

    // === Smoothed metrics ===
    public double avgVolUsd = 0.0;
    public double avgVolatility = 0.0;
    public double avgOiUsd = 0.0;
    public double avgAggressorVol = 0.0;

    // === Last tick state ===
    public double lastPrice = 0.0;
    public double lastFunding = 0.0;

    // === Smart-money metrics ===
    public double avgDeltaBuy = 0.0; // если используешь дельту — оставляем

    // === Control ===
    public long cooldownUntil = 0;
    public long lastSignalAtMs = 0;
    public int watchStreak = 0;
    public int enterStreak = 0;

    @Override
    public String toString() {
        return "SymbolState{oi=" + (oiList.isEmpty() ? 0 : oiList.getLast()) +
                ", vol=" + (volumes.isEmpty() ? 0 : volumes.getLast()) + "}";
    }
}
