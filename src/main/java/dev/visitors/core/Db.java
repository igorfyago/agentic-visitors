package dev.visitors.core;

import org.flywaydb.core.Flyway;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * This service's own database.
 *
 * It shares a Postgres SERVER with minimart, minipay and minianalytics because
 * one small machine is paying for all of them, and it shares nothing else: no
 * schema, no table, no transaction. Nothing here can read minimart's data even
 * to settle an argument, which is the only version of "separate service" worth
 * the name.
 *
 * The ledger primitives underneath (Ledger, Pool, Json) are COPIED from
 * minimart rather than depended on. That is deliberate duplication, and it is
 * what service-per-domain costs: a shared internal jar across four services is
 * how a distributed monolith is born, because every schema change becomes a
 * four-repository coordinated release. When the primitive has genuinely
 * stopped changing it is worth publishing as an artifact. Not before.
 */
public final class Db {

    // Declared FIRST on purpose. A static field initialised after the fields
    // that read it is null when they run, and here that would not throw: every
    // lookup would quietly fall through to the neutral default and the whole
    // thing would connect to the wrong database while looking fine.
    private static final java.util.Properties LOCAL = loadLocal();

    private static java.util.Properties loadLocal() {
        java.util.Properties p = new java.util.Properties();
        try (java.io.InputStream in = Db.class.getResourceAsStream("/db.properties")) {
            if (in != null) p.load(in);
        } catch (java.io.IOException ignored) {
            // absent is the normal case in a deployed environment, where every
            // value arrives from the environment instead
        }
        return p;
    }

    // CREDENTIALS AND ADDRESSES DO NOT BELONG IN JAVA.
    //
    // The seam test scans every source file in this package for the name of any
    // platform, and it found this class, because the connection defaults had
    // the neighbouring service's name in them. It was right to. Even though a
    // shared database SERVER is deployment config rather than platform
    // knowledge, a core that has to be edited when a neighbour is renamed is
    // not as independent as it claims.
    //
    // Environment first, then a local development properties file, then a
    // neutral default. "postgres" is the admin database because every Postgres
    // server has one, which is a better assumption than the name of whichever
    // service happened to be installed first.
    private static final String ADMIN_URL =
            conf("VISITORS_ADMIN_URL", "admin.url", "jdbc:postgresql://localhost:5436/postgres");
    public static final String URL =
            conf("VISITORS_DB_URL", "db.url", "jdbc:postgresql://localhost:5436/agentic_visitors");
    public static final String USER = conf("VISITORS_DB_USER", "db.user", "postgres");
    public static final String PASSWORD = conf("VISITORS_DB_PASSWORD", "db.password", "postgres");

    private static final Pool POOL =
            new Pool(URL, USER, PASSWORD, Integer.parseInt(conf("VISITORS_POOL", "db.pool", "8")));

    private Db() {}

    public static Connection open() throws SQLException {
        return POOL.borrow(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    public static void bootstrap() throws SQLException {
        try (Connection c = DriverManager.getConnection(ADMIN_URL, USER, PASSWORD);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM pg_database WHERE datname = 'agentic_visitors'")) {
            if (!rs.next()) {
                try (Statement create = c.createStatement()) {
                    create.execute("CREATE DATABASE agentic_visitors");
                }
            }
        }
        Flyway.configure().dataSource(URL, USER, PASSWORD)
                .locations("classpath:db")
                .baselineOnMigrate(true).baselineVersion("0")
                .load().migrate();
    }

    private static String conf(String envKey, String fileKey, String fallback) {
        String v = System.getenv(envKey);
        if (v != null && !v.isBlank()) return v;
        v = LOCAL.getProperty(fileKey);
        return v == null || v.isBlank() ? fallback : v;
    }
}
