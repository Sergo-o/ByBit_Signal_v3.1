//package stats;
//
//import java.sql.*;
//import java.util.List;
//
///**
// * Простой JDBC-репозиторий.
// * Использует SQLite (jdbc:sqlite:), но SQL совместим почти со всеми БД.
// */
//public final class SignalSqlRepository implements AutoCloseable {
//
//    private final Connection conn;
//
//    public SignalSqlRepository(String url) {
//        try {
//            this.conn = DriverManager.getConnection(url);
//            this.conn.setAutoCommit(false);
//
//            // ✅ Автосоздание таблиц
//            stats.DatabaseInit.init(this.conn);
//
//        } catch (SQLException e) {
//            throw new RuntimeException(e);
//        } catch (Exception e) {
//            throw new RuntimeException(e);
//        }
//    }
//
//
//
//    private void initSchema() throws SQLException {
//        try (Statement st = conn.createStatement()) {
//            // см. раздел 4 — таблицы такие же
//            st.execute("""
//                CREATE TABLE IF NOT EXISTS signals (
//                  id TEXT PRIMARY KEY,
//                  symbol TEXT NOT NULL,
//                  created_at_ms INTEGER NOT NULL,
//                  stage TEXT NOT NULL,
//                  direction TEXT NOT NULL,
//                  init_price REAL NOT NULL,
//                  init_score REAL NOT NULL,
//                  is_micro INTEGER NOT NULL,
//                  max_return_pct REAL DEFAULT 0,
//                  min_return_pct REAL DEFAULT 0
//                );
//            """);
//            st.execute("""
//                CREATE TABLE IF NOT EXISTS snapshots (
//                  signal_id TEXT NOT NULL,
//                  ts_ms INTEGER NOT NULL,
//                  price REAL,
//                  oi_now REAL,
//                  vol_now REAL,
//                  buy_ratio REAL,
//                  volt_rel REAL,
//                  funding REAL,
//                  peak_profit_pct REAL,
//                  drawdown_pct REAL,
//                  note TEXT,
//                  PRIMARY KEY(signal_id, ts_ms),
//                  FOREIGN KEY(signal_id) REFERENCES signals(id) ON DELETE CASCADE
//                );
//            """);
//            st.execute("""
//                CREATE TABLE IF NOT EXISTS outcomes (
//                  signal_id TEXT PRIMARY KEY,
//                  closed_at_ms INTEGER,
//                  peak_profit_pct REAL,
//                  max_drawdown_pct REAL,
//                  label TEXT,            -- "win"/"loss"/"neutral"
//                  comment TEXT,
//                  FOREIGN KEY(signal_id) REFERENCES signals(id) ON DELETE CASCADE
//                );
//            """);
//            st.execute("""
//                CREATE TABLE IF NOT EXISTS params_history (
//                  id INTEGER PRIMARY KEY AUTOINCREMENT,
//                  symbol TEXT NOT NULL,
//                  ts_ms INTEGER NOT NULL,
//                  min_streak INTEGER,
//                  min_spike_x REAL,
//                  min_dominance REAL,
//                  min_abs_usd REAL,
//                  winrate REAL,
//                  peak REAL,
//                  dd REAL,
//                  throughput REAL
//                );
//            """);
//        }
//        conn.commit();
//    }
//
//    public void upsertSignal(SignalRecord r) throws SQLException {
//        try (PreparedStatement ps = conn.prepareStatement("""
//            INSERT INTO signals(id, symbol, created_at_ms, stage, direction, init_price, init_score, is_micro, max_return_pct, min_return_pct)
//            VALUES(?,?,?,?,?,?,?,?,?,?)
//            ON CONFLICT(id) DO UPDATE SET
//              max_return_pct=excluded.max_return_pct,
//              min_return_pct=excluded.min_return_pct
//        """)) {
//            ps.setString(1, r.id);
//            ps.setString(2, r.symbol);
//            ps.setLong(3, r.createdAtMs);
//            ps.setString(4, r.stage);
//            ps.setString(5, r.direction);
//            ps.setDouble(6, r.initPrice);
//            ps.setDouble(7, r.initScore);
//            ps.setInt(8, r.isMicro ? 1 : 0);
//            ps.setDouble(9, r.maxReturn * 100.0);
//            ps.setDouble(10, r.minReturn * 100.0);
//            ps.executeUpdate();
//        }
//    }
//
//    public void insertSnapshots(String signalId, List<SignalSnapshot> snaps) throws SQLException {
//        try (PreparedStatement ps = conn.prepareStatement("""
//            INSERT OR REPLACE INTO snapshots(signal_id, ts_ms, price, oi_now, vol_now, buy_ratio, volt_rel, funding, peak_profit_pct, drawdown_pct, note)
//            VALUES(?,?,?,?,?,?,?,?,?,?,?)
//        """)) {
//            for (SignalSnapshot s : snaps) {
//                ps.setString(1, signalId);
//                ps.setLong(2, s.timestampMs);
//                ps.setDouble(3, s.price);
//                ps.setDouble(4, s.oiNow);
//                ps.setDouble(5, s.volNow);
//                ps.setDouble(6, s.buyRatio);
//                ps.setDouble(7, s.voltRel);
//                ps.setDouble(8, s.funding);
//                ps.setDouble(9, s.peakProfit);
//                ps.setDouble(10, s.drawdown);
//                ps.setString(11, s.note);
//                ps.addBatch();
//            }
//            ps.executeBatch();
//        }
//    }
//
//    public void upsertOutcome(String signalId, long closedAtMs, double peakProfitPct, double maxDrawdownPct, String label, String comment) throws SQLException {
//        try (PreparedStatement ps = conn.prepareStatement("""
//            INSERT INTO outcomes(signal_id, closed_at_ms, peak_profit_pct, max_drawdown_pct, label, comment)
//            VALUES(?,?,?,?,?,?)
//            ON CONFLICT(signal_id) DO UPDATE SET
//              closed_at_ms=excluded.closed_at_ms,
//              peak_profit_pct=excluded.peak_profit_pct,
//              max_drawdown_pct=excluded.max_drawdown_pct,
//              label=excluded.label,
//              comment=excluded.comment
//        """)) {
//            ps.setString(1, signalId);
//            ps.setLong(2, closedAtMs);
//            ps.setDouble(3, peakProfitPct);
//            ps.setDouble(4, maxDrawdownPct);
//            ps.setString(5, label);
//            ps.setString(6, comment);
//            ps.executeUpdate();
//        }
//    }
//
//    public void insertParamsSnapshot(String symbol,
//                                     long tsMs,
//                                     int minStreak,
//                                     double minSpikeX,
//                                     double minDominance,
//                                     double minAbsUsd,
//                                     double winrate,
//                                     double peak,
//                                     double dd,
//                                     double throughput) throws SQLException {
//        try (PreparedStatement ps = conn.prepareStatement("""
//            INSERT INTO params_history(symbol, ts_ms, min_streak, min_spike_x, min_dominance, min_abs_usd, winrate, peak, dd, throughput)
//            VALUES(?,?,?,?,?,?,?,?,?,?)
//        """)) {
//            ps.setString(1, symbol);
//            ps.setLong(2, tsMs);
//            ps.setInt(3, minStreak);
//            ps.setDouble(4, minSpikeX);
//            ps.setDouble(5, minDominance);
//            ps.setDouble(6, minAbsUsd);
//            ps.setDouble(7, winrate);
//            ps.setDouble(8, peak);
//            ps.setDouble(9, dd);
//            ps.setDouble(10, throughput);
//            ps.executeUpdate();
//        }
//    }
//
//    public void commit() throws SQLException { conn.commit(); }
//
//    @Override public void close() {
//        try { conn.commit(); } catch (Exception ignored) {}
//        try { conn.close(); }  catch (Exception ignored) {}
//    }
//}
