package state;

import java.util.ArrayDeque;
import java.util.Deque;

public class SymbolState {

    // === Price / volumes / OI series ===
    public final Deque<Double> closes = new ArrayDeque<>();
    public final Deque<Double> volumes = new ArrayDeque<>();
    public final Deque<Double> oiList = new ArrayDeque<>();

    private long startMs = System.currentTimeMillis();


    // === Aggressor streams (last N trades) ===
    public final Deque<Boolean> aggressorDirections = new ArrayDeque<>();
    public final Deque<Double> aggressorVolumes = new ArrayDeque<>();

    // === One-minute live flows ===
    public double buyAgg1m = 0.0;
    public double sellAgg1m = 0.0;

    public double liqBuy1m = 0.0; // сумма ликвидаций шортов (buy pressure)
    public double liqSell1m = 0.0; // сумма ликвидаций лонгов (sell pressure)

    public double oiVelocity = 0.0;
    public double oiAcceleration = 0.0;

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
    private long cooldownUntil = 0;
    private long lastSignalAtMs = 0;

    public void setCooldownUntil(long cooldownUntil) {
        this.cooldownUntil = cooldownUntil;
    }

    public void setLastSignalAtMs(long lastSignalAtMs) {
        this.lastSignalAtMs = lastSignalAtMs;
    }

    public void setWatchStreak(int watchStreak) {
        this.watchStreak = watchStreak;
    }

    public void setEnterStreak(int enterStreak) {
        this.enterStreak = enterStreak;
    }

    public long getStartMs() {
        return startMs;
    }

    public long getCooldownUntil() {
        return cooldownUntil;
    }

    public long getLastSignalAtMs() {
        return lastSignalAtMs;
    }

    public int getWatchStreak() {
        return watchStreak;
    }

    public int getEnterStreak() {
        return enterStreak;
    }

    private int watchStreak = 0;
    private int enterStreak = 0;

    @Override
    public String toString() {
        return "SymbolState{oi=" + (oiList.isEmpty() ? 0 : oiList.getLast()) +
                ", vol=" + (volumes.isEmpty() ? 0 : volumes.getLast()) + "}";
    }
}
