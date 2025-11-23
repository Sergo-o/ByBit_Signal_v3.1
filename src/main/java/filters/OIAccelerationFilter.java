package filters;

import log.FilterLog;
import state.SymbolState;
import app.Settings;
import tuning.AutoTuner;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * Адаптивный OI-фильтр:
 *  - смотрит скорость и ускорение OI,
 *  - использует относительную "нервность" OI (volRel по OI),
 *  - учитывает профиль micro / non-micro,
 *  - умеет работать в TRAIN-режиме (логируем, но не блокируем сигнал).
 *
 * Работает только по oiList в SymbolState, новых полей не добавляет.
 */
public final class OIAccelerationFilter {

    private OIAccelerationFilter() {}

    // Сколько последних значений OI используем для оценки "нервности" (volRel)
    private static final int VOLREL_WINDOW = 6;

    // Базовые пороги, если нет автотюнера
    private static final double BASE_MIN_VEL   = 0.0010;
    private static final double BASE_MIN_ACCEL = 0.0005;

    /**
     * Главная точка входа.
     *
     * @param s      состояние символа
     * @param symbol тикер (нужен только для логов)
     * @return true  — фильтр пропускает сигнал
     *         false — фильтр блокирует (кроме TRAIN-режима)
     */
    public static boolean pass(SymbolState s, String symbol) {
        // 0. Глобальный выключатель
        if (!Settings.OI_FILTER_ENABLED) {
            return true;
        }

        Deque<Double> oiList = s.oiList;
        if (oiList == null || oiList.size() < 3) {
            // слишком мало точек, чтобы оценивать скорость/ускорение
            return true;
        }

        double last = getFromEnd(oiList, 0);
        double prev1 = getFromEnd(oiList, 1);
        double prev2 = getFromEnd(oiList, 2);

        if (prev1 <= 0 || prev2 <= 0 || last <= 0) {
            return true;
        }

        double velNow = (last - prev1) / prev1;
        double velPrev = (prev1 - prev2) / prev2;
        double accel = velNow - velPrev;

        double volRel = computeVolRel(oiList, VOLREL_WINDOW);

        boolean isMicro = last > 0 && last < Settings.MICRO_OI_USD;

        // === Пороговые значения ===
        double minVel = BASE_MIN_VEL;   // 0.001
        double minAccel = BASE_MIN_ACCEL; // 0.0005

        if (isMicro) {
            minVel *= 1.2;
            minAccel *= 1.2;
        }

// считаем velNow, accel как у тебя

        boolean pass;

// 1) если OI падает — сразу режем
        if (velNow <= 0.0) {
            pass = false;
        } else {
            boolean strongVel = velNow >= minVel;
            boolean strongAccel = accel >= minAccel;

            // 2) достаточно ИЛИ сильной скорости, ИЛИ сильного ускорения
            pass = strongVel || strongAccel;
        }

// volRel сейчас только логируем, но НЕ используем в pass
        if (!pass && Settings.OI_FILTER_LOG_ENABLED) {
            String msg = String.format(
                    "vel=%.5f (min=%.5f) accel=%.5f (min=%.5f) volRel=%.4f micro=%s oi: %.0f→%.0f",
                    velNow, minVel, accel, minAccel, volRel, isMicro, prev1, last
            );
            FilterLog.logOiAccel(symbol, msg);
        }

        if (Settings.OI_TRAIN) {
            return true;
        }

        return pass;
    }


        /**
         * Взять элемент из конца декью:
         * offset=0 → последний,
         * offset=1 → предпоследний, и т.д.
         */
    private static double getFromEnd(Deque<Double> dq, int offsetFromEnd) {
        if (dq.isEmpty()) return 0.0;
        // Небольшой обход с конца — глубина маленькая (3–10), не страшно
        int idx = 0;
        Iterator<Double> it = dq.descendingIterator();
        while (it.hasNext()) {
            double v = it.next();
            if (idx == offsetFromEnd) {
                return v;
            }
            idx++;
        }
        return dq.getFirst();
    }

    /**
     * Оценка "нервности" OI по окну:
     * средний модуль относительного шага |Δoi/oi|.
     */
    private static double computeVolRel(Deque<Double> oiList, int window) {
        if (oiList.size() < 2) return 0.0;

        Deque<Double> buf = new ArrayDeque<>();
        Iterator<Double> it = oiList.descendingIterator();
        while (it.hasNext() && buf.size() < window) {
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
