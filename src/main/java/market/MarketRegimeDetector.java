package market;

import app.Settings;
import core.PumpLiquidityAnalyzer;
import state.SymbolState;

import static app.Settings.*;

import java.util.Iterator;

public class MarketRegimeDetector {

    private final PumpLiquidityAnalyzer analyzer;

    public MarketRegimeDetector(PumpLiquidityAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    public Regime getRegime() {
        // Используем BTCUSDT как прокси рынка
        SymbolState s = analyzer.getSymbolState("BTCUSDT");
        if (s == null || s.closes.size() < Math.max(MIN_BARS_FOR_ANALYSIS, REGIME_WINDOW_BARS)) {
            // Недостаточно данных — не мешаем, считаем TRENDING по умолчанию
            return Regime.TRENDING;
        }

        double slope = computeSlope(s, REGIME_WINDOW_BARS);      // относительный наклон за окно
        double voltRel = currentVoltRel(s);                      // относительная волатильность

        // Простая логика классификации:
        if (Math.abs(slope) >= REGIME_MIN_SLOPE && voltRel >= REGIME_VOL_LOW_X) {
            return Regime.TRENDING;
        }
        if (Math.abs(slope) < REGIME_MIN_SLOPE && voltRel <= REGIME_VOL_LOW_X) {
            return Regime.ACCUMULATION;
        }
        return Regime.CHOP;
    }

    public String debugSummary() {
        SymbolState s = analyzer.getSymbolState("BTCUSDT");
        if (s == null || s.closes.size() < Math.max(MIN_BARS_FOR_ANALYSIS, REGIME_WINDOW_BARS)) {
            return "[Regime] BTC insufficient data";
        }
        double slope = computeSlope(s, REGIME_WINDOW_BARS);
        double voltRel = currentVoltRel(s);
        return String.format("[Regime] %s | slope=%.4f (%.2f%%/win) voltRel=%.2f",
                getRegime().name(), slope, slope*100.0, voltRel);
    }

    private static double computeSlope(SymbolState s, int bars) {
        if (s.closes.size() < bars) return 0.0;
        Iterator<Double> it = s.closes.descendingIterator();
        double last = it.next();
        double prev = last;
        for (int i = 1; i < bars && it.hasNext(); i++) {
            prev = it.next();
        }
        if (prev <= 0) return 0.0;
        // относительное изменение за окно
        return (last / prev) - 1.0;
    }

    private static double currentVoltRel(SymbolState s) {
        if (s.closes.size() < 2) return 1.0;
        Iterator<Double> it = s.closes.descendingIterator();
        double last = it.next();
        double prev = it.next();
        double volt = Math.abs(last / prev - 1.0);
        return (s.avgVolatility > 0) ? (volt / s.avgVolatility) : 1.0;
    }
}
