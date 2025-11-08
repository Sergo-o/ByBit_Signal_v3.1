package stats;

/** Одна точка в треке сигнала (снапшот). */
public class SignalSnapshot {
    public final long timestampMs;
    public final double price;
    public final double oiNow;
    public final double volNow;
    public final double buyRatio;
    public final double voltRel;
    public final double funding;
    public final String note;      // произвольная пометка

    // Модели эффективности
    public final double peakProfit; // %, относительно цены входа
    public final double drawdown;   // %, относительно цены входа

    public SignalSnapshot(long timestampMs,
                          double price,
                          double oiNow,
                          double volNow,
                          double buyRatio,
                          double voltRel,
                          double funding,
                          String note,
                          double peakProfit,
                          double drawdown) {
        this.timestampMs = timestampMs;
        this.price = price;
        this.oiNow = oiNow;
        this.volNow = volNow;
        this.buyRatio = buyRatio;
        this.voltRel = voltRel;
        this.funding = funding;
        this.note = note;
        this.peakProfit = peakProfit;
        this.drawdown = drawdown;
    }
}
