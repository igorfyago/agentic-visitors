package dev.visitors;

import dev.visitors.core.Db;
import dev.visitors.core.Json;
import dev.visitors.intent.Grammar;
import dev.visitors.intent.Parsed;
import dev.visitors.platform.Manifest;
import dev.visitors.run.Executor;
import dev.visitors.run.Keys;
import dev.visitors.run.Memo;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE WHOLE THING, AGAINST A REAL PLATFORM.
 *
 * Every other lesson in this repo runs against a fake, which is correct for
 * testing the shell and worthless for the claim this slice actually makes. A
 * suite that stays green with minimart uninstalled has not demonstrated that a
 * visitor can do anything, only that the machinery is self-consistent.
 *
 * So this one drives the real minimart over a real socket: browse, subscribe to
 * something it learned from the response, check that it is really subscribed,
 * and cancel. It is SKIPPED rather than failed when minimart is not running,
 * because a red suite on a developer's laptop for want of a neighbouring
 * service teaches people to ignore red suites.
 *
 * The task is deliberately the one that is easy to fake: "subscribe, then
 * cancel before it renews". Faking it means asserting the visitor issued the
 * calls. Doing it properly means asking the platform afterwards, through its
 * own API, whether the subscription is actually gone.
 */
class MinimartTaskLessonTest {

    static final Instant T0 = Instant.parse("2027-04-01T00:00:00Z");
    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    static Manifest m;
    static String base;

    @BeforeAll
    static void boot() throws Exception {
        Db.bootstrap();
        // the manifest points at 8081; a locally running instance may be
        // anywhere, so the address is the one thing the test may override
        base = System.getenv().getOrDefault("MINIMART_URL", "http://localhost:8181");
        m = Manifest.of(manifestText(base));
        Assumptions.assumeTrue(reachable(), "minimart is not running at " + base + ", skipping");
    }

    @BeforeEach
    void reset() throws Exception {
        try (Connection c = Db.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE platform_calls, decisions, holds, entries, transactions, accounts, cassettes, runs RESTART IDENTITY CASCADE");
        }
    }

    /**
     * LESSON 1 · A VISITOR COMPLETES A REAL TASK THROUGH THE PUBLIC API ALONE.
     *
     * Nothing here reaches into minimart's database, and the visitor may only
     * name a product it saw in a response. If the catalogue read failed, the
     * subscribe could not even be formed, which is the grounding gate doing its
     * job rather than a convenience.
     *
     * The task is graded by ASKING THE PLATFORM, not by checking what the
     * visitor sent. A visitor that issues a cancel and a platform that ignores
     * it look identical from the client side, and only one of them is success.
     */
    @Test
    void lesson1_subscribe_then_cancel_verified_by_asking_the_platform() throws Exception {
        UUID run = SeamLessonTest.newRun();
        Executor exec = new Executor(m, run);
        Memo memo = new Memo();
        int visitor = 7;
        long identity = Keys.identity(m.identityBase(), run, visitor);

        // 1 · BROWSE. The only honest way to learn a product id.
        Executor.Result browsed = exec.run(m.cap("BROWSE"),
                Grammar.parse("BROWSE", m, memo::has, identity).intent(),
                SeamLessonTest.decision(run, visitor, 0, 0, "BROWSE"), visitor, 0, 0, T0);
        assertEquals("LANDED", browsed.outcome(), "the catalogue answered: " + browsed.body());
        memo.absorb(m.cap("BROWSE"), browsed.body());
        assertFalse(memo.set("products").isEmpty(),
                "and the visitor now knows what exists, from the response and nowhere else");

        String product = memo.set("products").get(0);

        // 2 · SUBSCRIBE to something it actually saw
        Parsed sub = Grammar.parse("SUBSCRIBE variant=" + product + " interval_days=30", m, memo::has, identity);
        assertTrue(sub.ok(), "a grounded proposal");
        Executor.Result subscribed = exec.run(m.cap("SUBSCRIBE"), sub.intent(),
                SeamLessonTest.decision(run, visitor, 0, 1, "SUBSCRIBE"), visitor, 0, 1, T0);
        assertEquals("LANDED", subscribed.outcome(), "subscribe answered: " + subscribed.body());

        // 3 · CHECK. Not "I assume it worked", but "the platform says so".
        Executor.Result checked = exec.run(m.cap("CHECK_SUBS"),
                Grammar.parse("CHECK_SUBS", m, memo::has, identity).intent(),
                SeamLessonTest.decision(run, visitor, 0, 2, "CHECK_SUBS"), visitor, 0, 2, T0);
        assertEquals("LANDED", checked.outcome());
        memo.absorb(m.cap("CHECK_SUBS"), checked.body());
        assertEquals(1, memo.set("subscriptions").size(),
                "exactly one subscription, and the visitor learned its id by asking: " + checked.body());
        assertTrue(checked.body().contains("\"status\":\"active\""), checked.body());

        // 4 · CANCEL the one it holds. It could not name any other, because it
        // has never seen any other.
        String subscriptionId = memo.set("subscriptions").get(0);
        Parsed cancel = Grammar.parse("CANCEL subscription=" + subscriptionId + " at_period_end=false",
                m, memo::has, identity);
        assertTrue(cancel.ok());
        Executor.Result canceled = exec.run(m.cap("CANCEL"), cancel.intent(),
                SeamLessonTest.decision(run, visitor, 0, 3, "CANCEL"), visitor, 0, 3, T0);
        assertEquals("LANDED", canceled.outcome(), canceled.body());

        // 5 · GRADE IT BY ASKING, not by trusting what we sent
        String after = get("/api/subscriptions?customer=" + identity);
        assertTrue(after.contains("\"status\":\"canceled\""),
                "THE PLATFORM AGREES IT IS CANCELLED, which is the only version of this that counts: " + after);
        System.out.println("lesson 1: browsed, subscribed to " + product
                + ", verified, cancelled, and the platform confirms it");
    }

    /**
     * LESSON 2 · A HALLUCINATED PRODUCT NEVER REACHES THE REAL PLATFORM.
     *
     * The grounding gate, proven where it matters. minimart cannot express
     * refusal on this endpoint: MartApi.subscribe answers 500 for a bad product
     * exactly as it would for a database outage, so an invented product would
     * be recorded as a platform ERROR and would pollute any error budget.
     *
     * The gate means the request is never sent at all, so there is nothing to
     * misclassify.
     */
    @Test
    void lesson2_an_invented_product_is_stopped_before_the_socket_opens() throws Exception {
        UUID run = SeamLessonTest.newRun();
        Executor exec = new Executor(m, run);
        Memo memo = new Memo();
        long identity = Keys.identity(m.identityBase(), run, 8);

        Executor.Result browsed = exec.run(m.cap("BROWSE"),
                Grammar.parse("BROWSE", m, memo::has, identity).intent(),
                SeamLessonTest.decision(run, 8, 0, 0, "BROWSE"), 8, 0, 0, T0);
        memo.absorb(m.cap("BROWSE"), browsed.body());

        Parsed invented = Grammar.parse("SUBSCRIBE variant=v-premium-platinum-90 interval_days=30",
                m, memo::has, identity);
        assertFalse(invented.ok(), "a product minimart has never heard of");

        long callsBefore = callCount(run);
        // nothing to execute: there is no Intent to pass, which is the type
        // system enforcing the gate rather than a runtime check hoping to
        assertNull(invented.intent());
        assertEquals(callsBefore, callCount(run), "and not one request was sent");
        System.out.println("lesson 2: an invented product produced no Intent and therefore no request");
    }

    /**
     * LESSON 3 · A RUN MUST NOT MEASURE ITS OWN HISTORY.
     *
     * This lesson exists because lesson 1 passed once and failed the second
     * time it was ever run. With identity derived from the visitor number
     * alone, visitor 7 was the same customer in every run, so the second run
     * opened by finding the first run's cancelled subscription already sitting
     * there and counted two.
     *
     * A run that inherits its own past is not measuring the thing it thinks it
     * is, and the failure appeared only because the suite happened to be run
     * twice in a row. Anything that only shows up on the second run of an
     * experiment deserves a test that runs it twice.
     */
    @Test
    void lesson3_two_runs_of_one_experiment_do_not_share_a_customer() throws Exception {
        UUID runA = SeamLessonTest.newRun(), runB = SeamLessonTest.newRun();

        long a = Keys.identity(m.identityBase(), runA, 7);
        long b = Keys.identity(m.identityBase(), runB, 7);
        assertNotEquals(a, b, "the same visitor in two runs is two different customers");
        assertTrue(a >= m.identityBase() && b >= m.identityBase(),
                "and both stay inside the space the manifest declared, away from other populations");

        // subscribe visitor 7 in BOTH runs, and neither sees the other
        for (UUID run : List.of(runA, runB)) {
            long identity = Keys.identity(m.identityBase(), run, 7);
            Executor exec = new Executor(m, run);
            Memo memo = new Memo();
            Executor.Result browsed = exec.run(m.cap("BROWSE"),
                    Grammar.parse("BROWSE", m, memo::has, identity).intent(),
                    SeamLessonTest.decision(run, 7, 0, 0, "BROWSE"), 7, 0, 0, T0);
            memo.absorb(m.cap("BROWSE"), browsed.body());
            String product = memo.set("products").get(0);
            exec.run(m.cap("SUBSCRIBE"),
                    Grammar.parse("SUBSCRIBE variant=" + product + " interval_days=30",
                            m, memo::has, identity).intent(),
                    SeamLessonTest.decision(run, 7, 0, 1, "SUBSCRIBE"), 7, 0, 1, T0);

            assertEquals(1, Json.each(get("/api/subscriptions?customer=" + identity), "id").size(),
                    "each run sees exactly its own subscription and none of the other's");
        }
        System.out.println("lesson 3: visitor 7 in two runs is two customers, " + a + " and " + b);
    }

    // ------------------------------------------------------------------ helpers

    private static boolean reachable() {
        try {
            HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/catalog")).GET()
                    .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).GET()
                .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    private static long callCount(UUID run) throws Exception {
        try (Connection c = Db.open();
             var ps = c.prepareStatement("SELECT COUNT(*) FROM platform_calls WHERE run_id = ?")) {
            ps.setObject(1, run);
            try (var rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    /** The shipped manifest, with only the address overridden. */
    private static String manifestText(String baseUrl) throws Exception {
        try (var in = Manifest.class.getResourceAsStream("/platforms/minimart.json")) {
            String text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return text.replace("\"platform.base_url\": \"http://localhost:8081\"",
                    "\"platform.base_url\": \"" + baseUrl + "\"");
        }
    }
}
