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

    private static final String ADMIN_URL =
            env("VISITORS_ADMIN_URL", "jdbc:postgresql://localhost:5436/minimart");
    public static final String URL =
            env("VISITORS_DB_URL", "jdbc:postgresql://localhost:5436/agentic_visitors");
    public static final String USER = env("VISITORS_DB_USER", "minimart");
    public static final String PASSWORD = env("VISITORS_DB_PASSWORD", "minimart");

    private static final Pool POOL =
            new Pool(URL, USER, PASSWORD, Integer.parseInt(env("VISITORS_POOL", "8")));

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

    private static String env(String k, String fallback) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? fallback : v;
    }
}
