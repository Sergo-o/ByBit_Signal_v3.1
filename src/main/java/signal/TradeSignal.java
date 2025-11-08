package signal;

public record TradeSignal(
        String symbol,
        Stage stage,
        String direction,
        double price,
        double score,
        SignalStrength strength,
        String reason,
        double oiNow,
        double volNow,
        double buyRatio,
        double voltRel,
        double fundingRate,
        boolean isMicro
) {}



