package filters;

import state.SymbolState;

public final class OIAccelerationFilter {

    // Параметры для порогов
    private static final double MIN_OI_VELOCITY = 0.0005; // 0.05%
    private static final double MIN_OI_ACCEL = 0.0002;    // 0.02%

    // Параметры для EMA и Z-score
    private static final double EMA_ALPHA = 0.3;          // Параметр сглаживания для EMA
    private static final double ZSCORE_THRESHOLD = 2.5;    // Порог Z-score для детекции аномалий

    // Метод для проверки сигнала
    public static boolean pass(SymbolState s) {
        if (s.oiList.size() < 3) return true; // Недостаточно истории - пропускаем

        // Получаем последние три значения OI
        Double oi0 = s.oiList.peekLast();                // текущий OI
        Double oi1 = s.oiList.toArray(new Double[0])[s.oiList.size() - 2]; // OI минута назад
        Double oi2 = s.oiList.toArray(new Double[0])[s.oiList.size() - 3]; // OI две минуты назад

        if (oi0 == null || oi1 == null || oi2 == null) return true;

        // Расчёт скорости и ускорения OI (velocity, acceleration)
        double velPrev = (oi1 - oi2) / Math.max(oi2, 1);
        double velNow  = (oi0 - oi1) / Math.max(oi1, 1);
        double accel = velNow - velPrev;

        // Сохраняем данные в состоянии для статистики/логов
        s.oiVelocity = velNow;
        s.oiAcceleration = accel;

        // Сглаживаем OI через EMA
        double smoothedOI = calculateEMA(s, oi0);

        // Расчёт Z-score для анализа аномальных скачков
        double zScore = calculateZScore(s, oi0);

        // Фильтрация по порогам
        boolean pass = (velNow >= MIN_OI_VELOCITY) &&
                (accel >= MIN_OI_ACCEL) &&
                (zScore > ZSCORE_THRESHOLD);  // Проверка по Z-score на аномальность

        return pass;
    }

    // Метод для расчёта EMA
    private static double calculateEMA(SymbolState s, double currentOI) {
        // Используем oiEma, которое добавляем в SymbolState
        Double lastEMA = s.oiEma;
        if (lastEMA == 0.0) {  // Если EMA ещё не рассчитано (значение по умолчанию — 0)
            lastEMA = currentOI;  // Тогда начнём с текущего OI
        }
        double newEMA = lastEMA + EMA_ALPHA * (currentOI - lastEMA);
        s.oiEma = newEMA;  // Сохраняем результат в состояние для дальнейших вычислений
        return newEMA;
    }

    // Метод для расчёта Z-score
    private static double calculateZScore(SymbolState s, double currentOI) {
        // Получаем OI за предыдущую минуту
        Double previousOI = s.oiList.toArray(new Double[0])[s.oiList.size() - 2]; // OI две минуты назад
        if (previousOI == null) return 0;  // Если данных для Z-score нет, возвращаем ноль

        double mean = (previousOI + currentOI) / 2;
        double stdDev = Math.abs(currentOI - mean);  // Простой расчёт стандартного отклонения
        double zScore = (currentOI - mean) / Math.max(stdDev, 1e-9);  // Защита от деления на 0
        return zScore;
    }
}
