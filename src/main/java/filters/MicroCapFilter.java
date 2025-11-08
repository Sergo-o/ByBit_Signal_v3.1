package filters;

import app.Settings;
import ml.MicroNN;
import state.SymbolState;

public final class MicroCapFilter {

    public static boolean pass(SymbolState s) {
        double oiLast = s.oiList.isEmpty() ? 0.0 : s.oiList.getLast();
        boolean isMicro = oiLast < Settings.MICRO_OI_USD;
        if (!isMicro) return true;

        // базовый OI-импульс
        double oiRel = (s.avgOiUsd > 0) ? (oiLast / s.avgOiUsd) : 1.0;
        if (oiRel < Settings.MICRO_OI_MIN_REL) return false;

        // slope
//        double prev = (s.prevOI > 0) ? s.prevOI : oiLast;
//        double slope = (oiLast - prev) / Math.max(prev, 1.0);
//        if (slope < Settings.MICRO_OI_MIN_SLOPE) return false;

        if (Settings.MICRO_NN_ENABLED) {
            double p = MicroNN.predict(s, true); // true = long-контекст по умолчанию
            if (p < Settings.MICRO_NN_THRESHOLD) {
                System.out.println("[FILTER|MICRO-NN] reject p=" + p);
                return false;
            }
        }
        return true;
    }
}
