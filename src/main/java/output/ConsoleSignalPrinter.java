package output;

import signal.TradeSignal;

public class ConsoleSignalPrinter implements SignalPrinter {

    @Override
    public void print(TradeSignal s) {
        String tag = s.isMicro() ? "(⚠️ микро)" : "";
        System.out.printf(
                """
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
%s %s — %s %s
Цена: %.4f
Сила сигнала: %.2f (%s)

Причина: %s

Метрики:
  • Открытый интерес (USD): %.0f
  • Минутный объём (USD): %.0f
  • Доля покупателей: %.2f
  • Волатильность×: %.2f
  • Funding: %.6f

Ссылки:
  • Coinglass: https://www.coinglass.com/tv/ru/Bybit_%s
  • Bybit:     https://www.bybit.com/trade/usdt/%s
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
""",
                s.symbol(), tag, s.stage(), s.direction(),
                s.price(), s.score(), s.strength(),
                s.reason(),
                s.oiNow(),
                s.volNow(),
                s.buyRatio(),
                s.voltRel(),
                s.fundingRate(),
                s.symbol(),
                s.symbol()
        );
    }
}
