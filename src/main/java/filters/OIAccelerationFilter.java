package filters;

import state.SymbolState;
import app.Settings;
import tuning.AutoTuner;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * Адаптивный OI-фильтр: скорость/ускорение + динамика порогов.
 * Не добавляет полей в SymbolState, работает только на oiList.
 */
public final class OIAccelerationFilter {

    private OIAccelerationFilter() {}

    // Базовые нижние пороги (как «пол»)
    // Если у тебя уже есть такие константы в Settings — можешь перекинуть их туда и использовать оттуда.
    private static final double BASE_MIN_OI_VELOCITY = 1e-6;   // 0.0001%
    private static final double BASE_MIN_OI_ACCEL     = 5e-7;  // 0.00005%

    // Максимум истории, по которой оценим «фон» скорости (чтобы не бегать по всей deque)
    private static final int HIST_BARS = 30;

    /**
     * Главный метод фильтра.
     * Возвращает true — если OI-импульс «достаточен».
     */
    public static boolean pass(SymbolState s) {
        Deque<Double> q = s.oiList;
        if (q == null || q.size() < 3) {
            // мало истории — не блокируем сигнал
            return true;
        }

        // Три последних значения OI
        // last = текущий, prev1 = минуту назад, prev2 = две минуты назад
        double last  = peekFromEnd(q, 0);
        double prev1 = peekFromEnd(q, 1);
        double prev2 = peekFromEnd(q, 2);

        if (prev1 <= 0 || prev2 <= 0) {
            return true;
        }

        // Скорость и ускорение (в долях)
        double velPrev = (prev1 - prev2) / prev2;
        double velNow  = (last  - prev1) / prev1;
        double accel   = velNow - velPrev;

        // --- Динамическая калибровка порогов ---

        // 1) «Фоновая» средняя скорость за HIST_BARS (в абсолюте)
        double meanAbsVel = meanAbsVelocity(q, Math.min(q.size(), HIST_BARS));

        // 2) Волатильность рынка: чем выше — тем выше пороги
        double volt = Math.max(1e-9, s.avgVolatility); // защита от 0

        // 3) «Микро»-профиль: для микро — пороги мягче
        boolean isMicro = s.avgOiUsd < Settings.MICRO_OI_USD;

        // Базовые пороги (мягкие → как в TRAIN, жёсткие → LIVE)
        // Если в Settings у тебя уже есть режим обучения — он подключается тут:
        boolean SOFT = Settings.OI_TRAINING_MODE; // true=мягко, false=жёстко

        double kSoft = SOFT ? 0.6 : 1.0;      // смягчение порогов в TRAIN
        double kMicro = isMicro ? 0.75 : 1.0; // мягче для микро

        // Заводим адаптивную «надбавку» от волатильности и «фоновой» скорости
        // Идея: если рынок шумный (volt большая), требуем больше vel/accel; если «фон» скорости большой — тоже.
        double adaptVel  = BASE_MIN_OI_VELOCITY + 0.75 * meanAbsVel + 0.5 * volt * 1e-3;
        double adaptAcc  = BASE_MIN_OI_ACCEL     + 0.50 * meanAbsVel + 0.4 * volt * 5e-4;

        // Итоговые пороги
        double minVel  = Math.max(BASE_MIN_OI_VELOCITY, adaptVel)  * kSoft * kMicro;
        double minAcc  = Math.max(BASE_MIN_OI_ACCEL,     adaptAcc) * kSoft * kMicro;

        // Подмешиваем базовые пороги из AutoTuner (если включён)
        if (Settings.AUTOTUNER_ENABLED) {
            AutoTuner.Profile profile =
                    isMicro ? AutoTuner.Profile.MICRO : AutoTuner.Profile.GLOBAL;
            AutoTuner.OIParams params = AutoTuner.getInstance().getOiParams(profile);

            minVel = Math.max(minVel, params.minVelBase);
            minAcc = Math.max(minAcc, params.minAccelBase);
        }


        // Проходим по условиям
        boolean pass = (velNow >= minVel) && (accel >= minAcc);

        // Отладка (ровно как ты логировал раньше)
        // Примечание: volRel берём 0..1 для информативности, без участия в решении
        double volRel = 0.0;
        Double lastVol = (s.volumes != null ? s.volumes.peekLast() : null);
        if (lastVol != null && s.avgVolUsd > 0) volRel = lastVol / s.avgVolUsd;

        if (!pass) {
//            System.out.printf(
//                    "[Filter] OIAcceler vel=%.5f (min=%.5f) accel=%.5f (min=%.5f) volRel=%.2f micro=%s oi: %.0f→%.0f%n",
//                    velNow, minVel, accel, minAcc, volRel, isMicro, prev1, last
//            );
        }
        return pass;
    }

    // === utils ===

    private static double peekFromEnd(Deque<Double> q, int offsetFromLast) {
        // offsetFromLast = 0 → последний; 1 → предыдущий; 2 → позапрошлый
        // Идём с конца итератором (Deque в проекте — LinkedList или ArrayDeque)
        Iterator<Double> it = q.descendingIterator();
        int i = 0;
        while (it.hasNext()) {
            double v = it.next();
            if (i == offsetFromLast) return v;
            i++;
        }
        // fallback
        return q.peekLast() != null ? q.peekLast() : 0.0;
    }

    private static double meanAbsVelocity(Deque<Double> q, int n) {
        if (n < 2) return 0.0;
        // берём n последних значений и считаем средний |delta / prev|
        ArrayDeque<Double> buf = new ArrayDeque<>(n);
        Iterator<Double> it = q.descendingIterator();
        while (it.hasNext() && buf.size() < n) {
            buf.addFirst(it.next());
        }
        double sum = 0.0;
        int cnt = 0;
        Double prev = null;
        for (Double v : buf) {
            if (prev != null && prev > 0) {
                sum += Math.abs((v - prev) / prev);
                cnt++;
            }
            prev = v;
        }
        return cnt > 0 ? (sum / cnt) : 0.0;
    }
}
