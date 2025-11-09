package app;

import core.MetricsProviderInit;
import core.PumpLiquidityAnalyzer;
import net.BybitRest;
import net.BybitWsClient;
import net.OiRestUpdater;
import output.ConsoleSignalPrinter;
import output.SignalPrinter;
import signal.TradeSignal;
import state.SymbolState;
import stats.SignalStatsService;
import store.MarketDataStore;

import java.sql.DriverManager;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main launcher with graceful STOP command:
 * Type "stop" in console -> bot stops creating new signals and waits
 * for all snapshots to finish, then exits safely.
 */
public class Main {

    private static volatile boolean stopRequested = false;

    public static void main(String[] args) throws Exception {

//        try (var conn = DriverManager.getConnection("jdbc:sqlite:signals.db")) {
//            stats.DatabaseInit.init(conn);
//        }

        Map<String, SymbolState> symbols = new ConcurrentHashMap<>();
        PumpLiquidityAnalyzer analyzer = new PumpLiquidityAnalyzer(symbols);
        MetricsProviderInit.init(analyzer);
        SignalStatsService.setMetricsProvider(new stats.AnalyzerMetricsProvider(analyzer));
        SignalPrinter printer = new ConsoleSignalPrinter();

        System.out.println("▶ Запуск WebSocket потоков...");

        // preload symbols
        BybitRest.preloadSymbols();
        OiRestUpdater.start();
        BybitWsClient.startTickers();
        Thread.sleep(1000);

        BybitWsClient.startKlines(analyzer);
        BybitWsClient.startTrades(analyzer);
        BybitWsClient.startLiquidations(analyzer);

        System.out.println("Монет получено: " + MarketDataStore.allSymbols().size());
        System.out.println("✅ WebSocket подключены");
        System.out.println("⏳ Ожидаем первые данные...");

        // ===== Поток чтения команды STOP =====
        new Thread(() -> {
            try (var scanner = new java.util.Scanner(System.in)) {
                while (true) {
                    String input = scanner.nextLine().trim().toLowerCase();

                    switch (input) {

                        case "stop":
                            System.out.println("\n🛑 Команда STOP получена!");
                            System.out.println("⏸ Останавливаем генерацию новых сигналов...");
                            stopRequested = true;
                            return;

                        case "train:on":
                            Settings.OI_TRAINING_MODE = true;
                            System.out.println("✅ [TRAIN MODE] OI обучение включено — фильтры смягчены");

                            // Ослабляем пороги прямо сейчас
                            filters.DynamicThresholds.MIN_STREAK = Math.max(1, filters.DynamicThresholds.MIN_STREAK - 1);
                            filters.DynamicThresholds.MIN_VOLUME_SPIKE_X *= 0.8;
                            filters.DynamicThresholds.MIN_DOMINANCE -= 0.05;

                            break;

                        case "train:off":
                            Settings.OI_TRAINING_MODE = false;
                            System.out.println("💎 [LIVE MODE] OI фильтр активирован — жесткие пороги");

                            // Вернём стандартные пороги
                            filters.DynamicThresholds.MIN_STREAK = 3;
                            filters.DynamicThresholds.MIN_VOLUME_SPIKE_X = 2.2;
                            filters.DynamicThresholds.MIN_DOMINANCE = 0.62;

                            break;

                        case "train:status":
                            System.out.println("📊 OI_TRAINING_MODE = " + Settings.OI_TRAINING_MODE);
                            break;

                        case "help":
                            System.out.println("""
                    📌 Доступные команды:
                       stop         — остановить генерацию сигналов
                       train:on     — включить режим обучения OI фильтра
                       train:off    — выключить обучение, включить фильтрацию
                       train:status — показать состояние тренировки
                       help         — команды помощи
                    """);
                            break;

                        default:
                            System.out.println("❓ Неизвестная команда. Напишите 'help'");
                    }
                }
            }
        }, "ConsoleCommandListener").start();

        // ===== основной цикл =====
        while (true) {

            if (!stopRequested) {
                // продолжаем нормальную логику
                for (String sym : MarketDataStore.allSymbols()) {
                    analyzer.analyze(sym).ifPresent(sig -> {
                        if (sig.stage() == signal.Stage.ENTER) {
                            printer.print(sig);
                            output.FileSignalLogger.log(sig);
                        }
                    });
                }
            } else {
                // STOP получен — только ждём окончания статистики
                if (SignalStatsService.getInstance().allCompleted()) {
                    System.out.println("✅ Все сигналы завершили сбор статистики!");
                    System.out.println("📁 Файлы сохранены в signal_exports/");
                    System.out.println("👋 Можно безопасно завершать программу.");
                    break;
                } else {
                    System.out.println("⏳ Ждём завершения статистики по сигналам...");
                }
            }

            Thread.sleep(60_000); // анализ раз в минуту
        }

        System.out.println("🚪 Завершение программы...");
        System.exit(0);
    }
}
