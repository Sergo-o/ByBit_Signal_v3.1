package filters;

import app.Settings;
import log.FilterLog;
import state.MarketSnapshot;
import state.SymbolState;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Сервис слежения за сигналами для фильтра разворота.
 * - startWatch(...) вызывается, когда мы сгенерили ENTER-сигнал.
 * - onKline(...) вызывается на каждый новый бар по символу.
 * - держит сигнал "в фокусе" до:
 *      * истечения REV_WATCH_MINUTES
 *      * или срабатывания разворота (PriceReversalFilter.isReversal == true)
 */
public final class ReversalWatchService {

    private static final ReversalWatchService INSTANCE = new ReversalWatchService();
    public static ReversalWatchService getInstance() { return INSTANCE; }

    private final Map<String, Watch> bySignal = new ConcurrentHashMap<>();
    private final Map<String, List<String>> bySymbol = new ConcurrentHashMap<>();

    private final PriceReversalFilter reversalFilter = new PriceReversalFilter();

    private static final class Watch {
        final String signalId;
        final String symbol;
        final boolean isLong;
        final long startMs;
        final long expireMs;
        volatile boolean done;

        Watch(String signalId, String symbol, boolean isLong, long startMs, long expireMs) {
            this.signalId = signalId;
            this.symbol   = symbol;
            this.isLong   = isLong;
            this.startMs  = startMs;
            this.expireMs = expireMs;
            this.done     = false;
        }
    }

    private ReversalWatchService() {}

    /** Запустить слежение за конкретным сигналом */
    public void startWatch(String signalId, String symbol, boolean isLong, long startMs) {
        if (!Settings.REV_WATCH_ENABLED) return;

        long ttlMs = Settings.REV_WATCH_MINUTES * 60_000L;
        long expire = startMs + ttlMs;

        Watch w = new Watch(signalId, symbol, isLong, startMs, expire);
        bySignal.put(signalId, w);

        bySymbol.computeIfAbsent(symbol, k -> new ArrayList<>()).add(signalId);

        FilterLog.log("REV_WATCH", symbol, String.format(
                "start watch signalId=%s isLong=%s ttlMin=%d",
                signalId, isLong, Settings.REV_WATCH_MINUTES
        ));
    }

    /** Остановить слежение вручную (если нужно) */
    public void stopWatch(String signalId) {
        Watch w = bySignal.remove(signalId);
        if (w == null) return;

        List<String> ids = bySymbol.get(w.symbol);
        if (ids != null) {
            ids.remove(signalId);
            if (ids.isEmpty()) {
                bySymbol.remove(w.symbol);
            }
        }
    }

    /**
     * Вызывается на каждый новый бар по symbol.
     * Проверяет все активные сигналы по этой монете.
     */
    public void onKline(String symbol, SymbolState s, MarketSnapshot snap) {
        if (!Settings.REV_WATCH_ENABLED) return;

        List<String> ids = bySymbol.get(symbol);
        if (ids == null || ids.isEmpty()) return;

        long now = System.currentTimeMillis();

        // копию делаем, чтобы можно было модифицировать список по ходу
        List<String> toCheck = new ArrayList<>(ids);

        for (String id : toCheck) {
            Watch w = bySignal.get(id);
            if (w == null || w.done) {
                continue;
            }

            // истекло время слежения
            if (now >= w.expireMs) {
                w.done = true;
                FilterLog.log("REV_WATCH", symbol, String.format(
                        "end watch (timeout) signalId=%s", w.signalId
                ));
                stopWatch(w.signalId);
                continue;
            }

            // проверка на разворот
            boolean reversal = reversalFilter.isReversal(symbol, s, snap);
            if (reversal) {
                w.done = true;

                // цена на текущем баре (последний close)
                double lastPrice = 0.0;
                if (!s.closes.isEmpty()) {
                    lastPrice = s.closes.getLast();
                }

                // добавляем снапшот REV_EXIT в статистику сигнала
                stats.SignalStatsService.getInstance().addReversalSnapshot(
                        w.signalId,
                        lastPrice,
                        snap.oiNow(),
                        snap.volNow(),
                        snap.buyRatio(),
                        snap.voltRel(),
                        0.0 // funding, если хочешь — можно позже подставить реальный
                );

                FilterLog.log("REV_EXIT", symbol, String.format(
                        "reversal exit candidate for signalId=%s isLong=%s",
                        w.signalId, w.isLong
                ));

                stopWatch(w.signalId);
            }
        }
    }
}
