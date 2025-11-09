package filters;

import state.SymbolState;
import tuning.AutoTuner;

public final class OIAccelerationFilter {

    private static final double EMA_ALPHA = 0.3;

    // базовые минимальные пороги (fallback)
    private static final double BASE_MIN_OI_VELOCITY = 0.0000001; //
    private static final double BASE_MIN_OI_ACCEL     = 0.00000005; //

    public static boolean pass(SymbolState s) {
        if (s.oiList.size() < 3) return true;

        Double oi0 = s.oiList.peekLast();
        Double oi1 = s.oiList.toArray(new Double[0])[s.oiList.size() - 2];
        Double oi2 = s.oiList.toArray(new Double[0])[s.oiList.size() - 3];

        if (oi0 == null || oi1 == null || oi2 == null) return true;

        double velPrev = (oi1 - oi2) / Math.max(oi2, 1);
        double velNow  = (oi0 - oi1) / Math.max(oi1, 1);
        double accel   = velNow - velPrev;

        s.oiVelocity = velNow;
        s.oiAcceleration = accel;

        boolean isMicro = s.avgOiUsd < AutoTuner.getInstance()
                .getParams(AutoTuner.Profile.GLOBAL)
                .microOiUsd;

        var p = AutoTuner.getInstance().getParams(
                isMicro ? AutoTuner.Profile.MICRO : AutoTuner.Profile.GLOBAL
        );

        double minVel   = Math.min(BASE_MIN_OI_VELOCITY, p.minOiImpulseX);
        double minAccel = Math.min(BASE_MIN_OI_ACCEL,     p.minOiImpulseX * 0.5);

        boolean pass =
                velNow >= minVel &&
                        accel  >= minAccel;

        if (pass) {
            AutoTuner.getInstance().onFilterPass(
                    isMicro ? AutoTuner.Profile.MICRO : AutoTuner.Profile.GLOBAL
            );
        }

        // логируем только когда не прошло
        if (!pass) {
            double lastVol = (s.volumes.peekLast() != null) ? s.volumes.peekLast() : 0;
            double volRel = (s.avgVolUsd > 0) ? lastVol / s.avgVolUsd : 0;

            System.out.printf(
                    "[Filter] OIAcceler vel=%.5f (min=%.5f) accel=%.5f (min=%.5f) volRel=%.2f micro=%s oi: %.0f→%.0f%n",
                    velNow, minVel, accel, minAccel, volRel, isMicro,
                    oi1, oi0
            );
        }

        return pass;
    }
}
