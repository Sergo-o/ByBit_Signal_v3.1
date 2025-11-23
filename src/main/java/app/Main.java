package app;

import core.MetricsProviderInit;
import core.PumpLiquidityAnalyzer;
import market.MarketRegimeDetector;
import net.BybitRest;
import net.BybitWsClient;
import net.OiRestUpdater;
import output.ConsoleSignalPrinter;
import output.SignalPrinter;
import signal.TradeSignal;
import state.SymbolState;
import stats.SignalStatsService;
import store.MarketDataStore;

import java.nio.file.Paths;
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

        Settings.RUNNING = true;

        // 1) Загружаем настройки
        try (var is = Main.class.getResourceAsStream("/settings.properties")) {
            if (is != null) {
                var props = new java.util.Properties();
                props.load(is);
                Settings.loadFrom(props);
                System.out.println("✅ settings.properties загружен");
            } else {
                System.out.println("⚠ settings.properties не найден, используются значения по умолчанию");
            }
        } catch (Exception e) {
            System.err.println("⚠ Не удалось загрузить settings.properties: " + e.getMessage());
        }

        Map<String, SymbolState> symbols = new ConcurrentHashMap<>();
        PumpLiquidityAnalyzer analyzer = new PumpLiquidityAnalyzer(symbols);
        final MarketRegimeDetector regimeDetector = new MarketRegimeDetector(analyzer);
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
//        BybitWsClient.startLiquidations(analyzer);

        System.out.println("Монет получено: " + MarketDataStore.allSymbols().size());
        System.out.println("✅ WebSocket подключены");
        System.out.println("⏳ Ожидаем первые данные...");

        // ===== Поток чтения команды STOP =====
        new Thread(() -> {
            try (var scanner = new java.util.Scanner(System.in)) {
                while (true) {
                    String input = scanner.nextLine().trim().toLowerCase();

                    switch (input) {

                        // ===== Служебные =====
                        case "stop":
                            System.out.println("\n🛑 Команда STOP получена!");
                            System.out.println("⏸ Останавливаем генерацию новых сигналов...");
                            app.Settings.RUNNING = false;
                            stopRequested = true;
                            return;

                        case "help":
                            System.out.println("""
                                    📌 Доступные команды:
                                       help               — показать это меню
                                       status             — текущее состояние фильтров/режимов
                                    
                                       oi:on              — включить OIAccelerationFilter
                                       oi:off             — выключить OIAccelerationFilter
                                       oi:train:on        — мягкий режим OI (не блокирует)
                                       oi:train:off       — строгий режим OI
                                    
                                       aggr:on            — включить AdaptiveAggressorFilter
                                       aggr:off           — выключить AdaptiveAggressorFilter
                                       aggr:train:on      — мягкий режим агрессора (смягчает пороги)
                                       aggr:train:off     — строгий режим агрессора (базовые пороги)
                                    
                                       burst:on           — включить AggressorBurstFilter
                                       burst:off          — выключить AggressorBurstFilter
                                       burst:train:on     — мягкий режим burst (не блокирует)
                                       burst:train:off    — строгий режим burst
                                    
                                       train:on           — мягкий режим СРАЗУ для всех фильтров
                                       train:off          — строгий режим СРАЗУ для всех фильтров
                                    """);
                            break;

                        case "status":
                            System.out.println("🔎 Состояние фильтров:");
                            System.out.println("  OI:     enabled=" + app.Settings.OI_FILTER_ENABLED + ", train=" + app.Settings.OI_TRAIN);
                            System.out.println("  AGGR:   enabled=" + app.Settings.AGGR_FILTER_ENABLED + ", train=" + app.Settings.AGGR_TRAIN);
                            System.out.println("  BURST:  enabled=" + app.Settings.BURST_FILTER_ENABLED + ", train=" + app.Settings.BURST_TRAIN);
                            System.out.println(regimeDetector.debugSummary());
                            break;

                        // ===== OIAccelerationFilter =====
                        case "oi:on":
                            app.Settings.OI_FILTER_ENABLED = true;
                            System.out.println("✅ [OI] включён");
                            break;

                        case "oi:off":
                            app.Settings.OI_FILTER_ENABLED = false;
                            System.out.println("🚫 [OI] выключен");
                            break;

                        case "oi:train:on":
                            app.Settings.OI_TRAIN = true;
                            app.Settings.OI_TRAINING_MODE = true; // если где-то ещё читается
                            System.out.println("✅ [OI TRAIN] включён: фильтр логирует, но НЕ блокирует");
                            break;

                        case "oi:train:off":
                            app.Settings.OI_TRAIN = false;
                            app.Settings.OI_TRAINING_MODE = false;
                            System.out.println("💎 [OI TRAIN] выключен: фильтр снова блокирует");
                            break;

                        // ===== AdaptiveAggressorFilter =====
                        case "aggr:on":
                            app.Settings.AGGR_FILTER_ENABLED = true;
                            System.out.println("✅ [AGGR] включён");
                            break;

                        case "aggr:off":
                            app.Settings.AGGR_FILTER_ENABLED = false;
                            System.out.println("🚫 [AGGR] выключен");
                            break;

                        case "aggr:train:on":
                            app.Settings.AGGR_TRAIN = true;
                            System.out.println("✅ [AGGR TRAIN] мягкий режим: смягчаем пороги");
                            filters.DynamicThresholds.softenForTrain();
                            break;

                        case "aggr:train:off":
                            app.Settings.AGGR_TRAIN = false;
                            System.out.println("💎 [AGGR TRAIN] строгий режим: базовые пороги");
                            filters.DynamicThresholds.restoreDefaults();
                            break;

                        // ===== AggressorBurstFilter =====
                        case "burst:on":
                            app.Settings.BURST_FILTER_ENABLED = true;
                            System.out.println("✅ [BURST] включён");
                            break;

                        case "burst:off":
                            app.Settings.BURST_FILTER_ENABLED = false;
                            System.out.println("🚫 [BURST] выключен");
                            break;

                        case "burst:train:on":
                            app.Settings.BURST_TRAIN = true;
                            System.out.println("✅ [BURST TRAIN] мягкий режим: не блокирует");
                            break;

                        case "burst:train:off":
                            app.Settings.BURST_TRAIN = false;
                            System.out.println("💎 [BURST TRAIN] строгий режим");
                            break;

                        // ===== Глобальные TRAIN on/off для всех =====
                        case "train:on":
                            app.Settings.OI_TRAIN = true;
                            app.Settings.AGGR_TRAIN = true;
                            app.Settings.BURST_TRAIN = true;
                            app.Settings.OI_TRAINING_MODE = true;
                            System.out.println("✅ [TRAIN MODE] включён для ВСЕХ фильтров");
                            filters.DynamicThresholds.softenForTrain();
                            break;

                        case "train:off":
                            app.Settings.OI_TRAIN = false;
                            app.Settings.AGGR_TRAIN = false;
                            app.Settings.BURST_TRAIN = false;
                            app.Settings.OI_TRAINING_MODE = false;
                            System.out.println("💎 [LIVE MODE] строгий режим для ВСЕХ фильтров");
                            filters.DynamicThresholds.restoreDefaults();
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

        BybitWsClient.shutdown();
        SignalStatsService.getInstance().shutdown();
        SignalStatsService.getInstance().exportAllToCsv(Paths.get("signal_exports/all_signals.csv"));
        System.out.println("🚪 Завершение программы...");
        System.exit(0);
    }
}
