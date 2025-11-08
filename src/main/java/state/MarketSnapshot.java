package state;

public record MarketSnapshot(
        double volNow, double volRel,
        double oiNow,  double oiRel,
        double flow,   double buyRatio,
        double deltaShift,
        double voltRel,
        double score
) {}


