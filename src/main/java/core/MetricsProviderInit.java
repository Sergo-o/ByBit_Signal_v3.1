package core;

import stats.AnalyzerMetricsProvider;
import stats.SignalStatsService;

public class MetricsProviderInit {

    public static void init(PumpLiquidityAnalyzer analyzer) {
        // СТАВИМ провайдера через публичный метод — без прямого доступа к приватному классу
        SignalStatsService.setMetricsProvider(new AnalyzerMetricsProvider(analyzer));
    }
}
