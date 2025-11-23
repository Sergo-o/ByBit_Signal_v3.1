package log;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Универсальный логер для фильтров и отладочных сообщений.
 *
 * Пишет строки вида:
 * 2025-02-01 12:34:56.789 [AGGR] [Thread-12] SOLUSDT flow=...
 *
 * Использование:
 *   FilterLog.log("AGGR", "SOLUSDT", "flow=12345 ratio=0.91 (min=0.68)");
 *   FilterLog.logIgnore("SOLUSDT", "Низкий расход: 1234");
 *   FilterLog.debug("PumpLiquidityAnalyzer", "symbol=SOLUSDT score=3.0");
 */
public final class FilterLog {

    private static final String LOG_DIR = "logs";
    private static final String LOG_FILE = "filters_massage.log";

    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static BufferedWriter writer;
    private static final Object LOCK = new Object();

    static {
        init();
    }

    private FilterLog() {}

    private static void init() {
        try {
            Path dir = Paths.get(LOG_DIR);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            writer = new BufferedWriter(new FileWriter(dir.resolve(LOG_FILE).toFile(), true));
        } catch (IOException e) {
            // Если логер не поднялся — пишем в консоль
            e.printStackTrace();
        }
    }

    /**
     * Базовый метод логирования.
     *
     * @param tag    тип сообщения: AGGR, OIACCEL, BURST, IGNORE, DEBUG и т.п.
     * @param symbol тикер (может быть null)
     * @param msg    текст сообщения без времени/префикса
     */
    public static void log(String tag, String symbol, String msg) {
        if (writer == null) {
            // резервно пишем в stdout, если файл не открылся
            System.out.printf("%s [%s] %s %s%n",
                    LocalDateTime.now().format(TS_FORMAT),
                    tag,
                    symbol != null ? symbol : "-",
                    msg
            );
            return;
        }

        String ts = LocalDateTime.now().format(TS_FORMAT);
        String thread = Thread.currentThread().getName();

        String line = String.format(
                "%s [%s] [%s] %s %s",
                ts,
                tag,
                thread,
                symbol != null ? symbol : "-",
                msg
        );

        synchronized (LOCK) {
            try {
                writer.write(line);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /** Для IGNORE-сообщений, чтобы не писать каждый раз tag руками. */
    public static void logIgnore(String symbol, String reason) {
        log("IGNORE", symbol, reason);
    }

    /** Для агрессор-фильтра. */
    public static void logAggr(String symbol, String msg) {
        log("AGGR", symbol, msg);
    }

    /** Для burst-фильтра. */
    public static void logBurst(String symbol, String msg) {
        log("BURST", symbol, msg);
    }

    /** Для OI-ускорения. */
    public static void logOiAccel(String symbol, String msg) {
        log("OIACCEL", symbol, msg);
    }

    /** Для произвольного дебага. */
    public static void debug(String source, String msg) {
        log("DEBUG", source, msg);
    }

    /** Можно вызывать при штатном завершении программы. */
    public static void shutdown() {
        synchronized (LOCK) {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                }
                writer = null;
            }
        }
    }
}
