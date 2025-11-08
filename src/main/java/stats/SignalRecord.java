package stats;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Карточка сигнала + динамические снапшоты. */
public class SignalRecord {
    public final String id;         // SYMBOL_ts
    public final String symbol;
    public final long createdAtMs;
    public final String stage;      // WATCH / ENTER
    public final String direction;  // LONG / SHORT
    public final double initPrice;
    public final double initScore;
    public final boolean isMicro;

    public final List<SignalSnapshot> snapshots =
            Collections.synchronizedList(new ArrayList<>());

    public volatile boolean completed = false;

    // Агрегаты для модели эффективности (в долях, не в %):
    public volatile double maxReturn = 0.0; // макс. (price/entry - 1)
    public volatile double minReturn = 0.0; // мин. (price/entry - 1)

    public SignalRecord(String id,
                        String symbol,
                        long createdAtMs,
                        String stage,
                        String direction,
                        double initPrice,
                        double initScore,
                        boolean isMicro) {
        this.id = id;
        this.symbol = symbol;
        this.createdAtMs = createdAtMs;
        this.stage = stage;
        this.direction = direction;
        this.initPrice = initPrice;
        this.initScore = initScore;
        this.isMicro = isMicro;
    }

    public void addSnapshot(SignalSnapshot s) { snapshots.add(s); }

    public SignalSnapshot last() {
        synchronized (snapshots) {
            if (snapshots.isEmpty()) return null;
            return snapshots.get(snapshots.size() - 1);
        }
    }
}
