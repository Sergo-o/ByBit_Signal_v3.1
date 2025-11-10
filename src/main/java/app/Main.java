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

                        // ====== OI TRAIN режим ======
                        case "train:on":
                            Settings.OI_TRAIN = true;
                            Settings.OI_FILTER_ENABLED = true;
                            System.out.println("✅ [TRAIN MODE] OI обучение включено — фильтр смягчён");

                            filters.DynamicThresholds.MIN_STREAK = Math.max(1, filters.DynamicThresholds.MIN_STREAK - 1);
                            filters.DynamicThresholds.MIN_VOLUME_SPIKE_X *= 0.8;
                            filters.DynamicThresholds.MIN_DOMINANCE -= 0.05;
                            break;

                        case "train:off":
                            Settings.OI_TRAIN = false;
                            Settings.OI_FILTER_ENABLED = true;
                            System.out.println("💎 [LIVE MODE] OI фильтр работает на полную");

                            filters.DynamicThresholds.MIN_STREAK = 3;
                            filters.DynamicThresholds.MIN_VOLUME_SPIKE_X = 2.2;
                            filters.DynamicThresholds.MIN_DOMINANCE = 0.62;
                            break;

                        case "train:status":
                            System.out.printf("📊 OI фильтр = %s | TRAIN режим = %s%n",
                                    Settings.OI_FILTER_ENABLED ? "ВКЛ" : "ВЫКЛ",
                                    Settings.OI_TRAIN ? "ДА" : "НЕТ");
                            break;

                        // ====== Агрессор-фильтр (AdaptiveAggressor) ======
                        case "aggr:on":
                            Settings.AGGR_FILTER_ENABLED = true;
                            System.out.println("✅ Агрессор-фильтр включён");
                            break;

                        case "aggr:off":
                            Settings.AGGR_FILTER_ENABLED = false;
                            System.out.println("⛔ Агрессор-фильтр отключён");
                            break;

                        case "aggr:train:on":
                            Settings.AGGR_TRAIN = true;
                            System.out.println("🟡 Агрессор-фильтр в мягком режиме (TRAIN)");
                            break;

                        case "aggr:train:off":
                            Settings.AGGR_TRAIN = false;
                            System.out.println("💎 Агрессор-фильтр в строгом режиме");
                            break;

                        // ====== Burst-фильтр (AggressorBurst) ======
                        case "burst:on":
                            Settings.BURST_FILTER_ENABLED = true;
                            System.out.println("✅ Burst-фильтр включён");
                            break;

                        case "burst:off":
                            Settings.BURST_FILTER_ENABLED = false;
                            System.out.println("⛔ Burst-фильтр отключён");
                            break;

                        case "burst:train:on":
                            Settings.BURST_TRAIN = true;
                            System.out.println("🟡 Burst-фильтр в мягком режиме (TRAIN)");
                            break;

                        case "burst:train:off":
                            Settings.BURST_TRAIN = false;
                            System.out.println("💎 Burst-фильтр в строгом режиме");
                            break;

                        // ====== Показать состояние всех фильтров ======
                        case "filters:status":
                            System.out.printf("""
                                            🔧 Состояние фильтров:
                                            OI:      [%s] TRAIN=%s
                                            Aggressor:[%s] TRAIN=%s
                                            Burst:   [%s] TRAIN=%s
                                            """,
                                    Settings.OI_FILTER_ENABLED ? "ВКЛ" : "ВЫКЛ",
                                    Settings.OI_TRAIN ? "ДА" : "НЕТ",
                                    Settings.AGGR_FILTER_ENABLED ? "ВКЛ" : "ВЫКЛ",
                                    Settings.AGGR_TRAIN ? "ДА" : "НЕТ",
                                    Settings.BURST_FILTER_ENABLED ? "ВКЛ" : "ВЫКЛ",
                                    Settings.BURST_TRAIN ? "ДА" : "НЕТ"
                            );
                            break;

                        // ====== Помощь ======
                        case "help":
                            System.out.println("""
                                    📌 Команды управления фильтрами:
                                    
                                    stop — остановить сигналы
                                    
                                    OI фильтр:
                                      train:on / train:off   — включить/выключить TRAIN режим
                                      train:status           — статус OI TRAIN
                                      oi:on / oi:off         — включить / выключить OI-фильтр
                                    
                                    Aggressor фильтр:
                                      aggr:on / aggr:off
                                      aggr:train:on / aggr:train:off
                                    
                                    Burst фильтр:
                                      burst:on / burst:off
                                      burst:train:on / burst:train:off
                                    
                                    filters:status — показывать все статусы
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
