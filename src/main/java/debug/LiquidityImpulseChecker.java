package debug;

import state.SymbolState;

public class LiquidityImpulseChecker {

    public static void debugCheck(String symbol, SymbolState s) {
        Double volNow = s.volumes.peekLast();
        Double oiNow  = s.oiList.peekLast();
        if (volNow == null || oiNow == null) return;

        double volRel = (s.avgVolUsd > 0) ? volNow / s.avgVolUsd : 0;
        double oiRel  = (s.avgOiUsd  > 0) ? oiNow  / s.avgOiUsd  : 0;

        boolean volBurst = volRel > 2.0;
        boolean oiPulse  = oiRel  > 1.01;
        boolean liqSupport = (s.liqBuy1m + s.liqSell1m) > 100_000;
        boolean priceImpulse = false; // можно усложнить при необходимости

        if (volBurst || oiPulse) {
            DebugPrinter.printImpulseCheck(
                    symbol,
                    (volBurst && liqSupport) || (oiPulse && volBurst),
                    oiPulse, volBurst, liqSupport, priceImpulse,
                    String.format("vol×=%.2f", volRel),
                    String.format("oi×=%.3f", oiRel)
            );
        }
    }

    public static void evaluate(signal.TradeSignal sig, SymbolState s) {
        // Пока оценка без влияния на решение — только подсветка/лог (можно расширить позже)
        Double volNow = s.volumes.peekLast();
        Double oiNow  = s.oiList.peekLast();
        if (volNow == null || oiNow == null) return;

        double volRel = (s.avgVolUsd > 0) ? volNow / s.avgVolUsd : 0;
        double oiRel  = (s.avgOiUsd  > 0) ? oiNow  / s.avgOiUsd  : 0;

        if (volRel > 2 || oiRel > 1.01) {
            DebugPrinter.printImpulse(sig.symbol(),
                    String.format("[детектор] impulse detected | vol×=%.2f oi×=%.3f", volRel, oiRel));
        }
    }
}

