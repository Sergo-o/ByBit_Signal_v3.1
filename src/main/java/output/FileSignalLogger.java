package output;

import signal.Stage;
import signal.TradeSignal;

import java.io.FileWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class FileSignalLogger {

    private static final String FILE_NAME = "signals.log";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void log(TradeSignal s) {
        if (s.stage() != Stage.ENTER) return; // логируем только ENTER
        try (FileWriter fw = new FileWriter(FILE_NAME, true)) {
            String dirRus = s.direction().equals("LONG") ? "Лонг" : "Шорт";
            String micro  = s.isMicro() ? " (⚠️ микро)" : "";
            String text = String.format("""
                    ================================
                    Время: %s
                    Монета: %s%s
                    Направление: %s
                    Сигнал: %s

                    Цена: %.4f
                    Сила сигнала: %.2f

                    Coinglass: https://www.coinglass.com/tv/ru/Bybit_%s
                    Bybit:     https://www.bybit.com/trade/usdt/%s

                    Причина: %s
                    ================================

                    """,
                    TS.format(LocalDateTime.now()),
                    s.symbol(), micro,
                    dirRus,
                    s.stage(),
                    s.price(),
                    s.score(),
                    s.symbol(),
                    s.symbol(),
                    s.reason()
            );
            fw.write(text);
        } catch (Exception ignore) {}
    }
}

