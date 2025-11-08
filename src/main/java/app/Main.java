package app;

import core.MetricsProviderInit;
import core.PumpLiquidityAnalyzer;
import net.BybitRest;
import net.BybitWsClient;
import net.OiRestUpdater;
import output.ConsoleSignalPrinter;
import output.SignalPrinter;
import signal.TradeSignal;
import stats.SignalStatsService;
import store.MarketDataStore;

import java.sql.DriverManager;

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

        PumpLiquidityAnalyzer analyzer = new PumpLiquidityAnalyzer();
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
                    String input = scanner.nextLine().trim();
                    if (input.equalsIgnoreCase("stop")) {
                        System.out.println("\n🛑 Команда STOP получена!");
                        System.out.println("⏸ Останавливаем генерацию новых сигналов...");
                        stopRequested = true;
                        break;
                    }
                }
            }
        }, "StopCommandListener").start();

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
