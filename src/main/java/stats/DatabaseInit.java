//package stats;
//
//import java.sql.Connection;
//import java.sql.Statement;
//
//public class DatabaseInit {
//
//    public static void init(Connection c) throws Exception {
//        try (Statement st = c.createStatement()) {
//            st.execute("""
//                CREATE TABLE IF NOT EXISTS signals(
//                    id TEXT PRIMARY KEY,
//                    symbol TEXT,
//                    ts INTEGER,
//                    stage TEXT,
//                    dir TEXT,
//                    price REAL,
//                    score REAL,
//                    isMicro INTEGER,
//                    maxReturn REAL,
//                    minReturn REAL
//                );
//            """);
//
//            st.execute("""
//                CREATE TABLE IF NOT EXISTS snapshots(
//                    signalId TEXT,
//                    ts INTEGER,
//                    price REAL,
//                    oi REAL,
//                    vol REAL,
//                    buyRatio REAL,
//                    voltRel REAL,
//                    funding REAL,
//                    peak REAL,
//                    dd REAL
//                );
//            """);
//
//            st.execute("""
//                CREATE TABLE IF NOT EXISTS params_snapshots(
//                    profile TEXT,
//                    ts INTEGER,
//                    streak REAL,
//                    volSpike REAL,
//                    dom REAL,
//                    absVol REAL,
//                    ewWin REAL,
//                    ewPeak REAL,
//                    ewDD REAL,
//                    ewTh REAL
//                );
//            """);
//        }
//    }
//}
