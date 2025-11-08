package state;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class SymbolState {
    public Deque<Double> closes = new ArrayDeque<>();
    public Deque<Double> volumes = new ArrayDeque<>();
    public Deque<Double> oiList = new ArrayDeque<>();

    public double lastPrice, lastFunding;
    public long lastMinute;

    public double buyAgg1m = 0, sellAgg1m = 0;
    public double liqBuy1m = 0, liqSell1m = 0;

    public double avgVolUsd = 0, avgOiUsd = 0;
    public double avgDeltaBuy = 0.5;
    public double avgVolatility = 0;

    public double oiEma = 0.0;  // Для хранения сглаженного значения OI

    // NEW: средний "живой" поток агрессора
    public double avgFlowUsd = 0.0;

    public int watchStreak = 0, enterStreak = 0;
    public String lastBias = null;

    public long lastSignalAtMs = 0;
    public long cooldownUntil = 0;

    // --- Ликвидации (EWMA и, по желанию, минутные счетчики) ---
    public double liqLongUsd = 0.0;   // EWMA по ликвидациям long-стороны (т.е. лонги выбивают)
    public double liqShortUsd = 0.0;  // EWMA по ликвидациям short-стороны (т.е. шорты выбивают)

    // Опционально: минутные суммы (если когда-нибудь понадобятся для "всплесков"):
    public double liqLong1m = 0.0;
    public double liqShort1m = 0.0;


    // ✅ Добавляем новые поля
    public double oiVelocity = 0.0;
    public double oiAcceleration = 0.0;

    // Для кластера агрессора
    public final List<Boolean> aggressorDirections = new ArrayList<>(); // true=buy, false=sell
    public final List<Double> aggressorVolumes = new ArrayList<>();

    public double avgAggressorVol = 0; // средний тик объем агрессора

}


