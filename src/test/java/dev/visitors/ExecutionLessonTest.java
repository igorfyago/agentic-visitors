package dev.visitors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.visitors.core.Db;
import dev.visitors.intent.Grammar;
import dev.visitors.platform.Manifest;
import dev.visitors.run.Calls;
import dev.visitors.run.Executor;
import dev.visitors.run.Keys;
import dev.visitors.run.Memo;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WHAT IS WRITTEN DOWN, AND WHEN.
 *
 * The obvious way to record an outbound call is to send it and then write what
 * happened. It reads perfectly and it has a hole: crash between the two and an
 * effect exists out in the world that this system has no record of causing.
 * Nothing detects that afterwards, because the only evidence the call happened
 * is the row that was never written.
 *
 * These lessons are about the two properties that close it. The intention is
 * committed BEFORE the socket opens, so a crash leaves an honest "we tried and
 * we do not know". And the key is derived from position rather than from
 * anything the model said, so replaying a window sends nothing new even though
 * the model is free to answer differently the second time.
 */
class ExecutionLessonTest {

    static final Instant T0 = Instant.parse("2027-03-01T00:00:00Z");
    static HttpServer slow;
    static final int SLOW_PORT = 18200;
    static final AtomicInteger received = new AtomicInteger();
    static volatile CountDownLatch holdResponse;

    static final String MANIFEST = """
            {
              "platform.id": "slow-thing",
              "platform.base_url": "http://localhost:%d",
              "platform.connect_timeout_ms": "1000",
              "platform.read_timeout_ms": "800",
              "platform.identity_base": "700",
              "alphabet": "POKE,IDLE",
              "fallback.intent": "IDLE",
              "clock.kind": "noop",
              "POKE.kind": "call",
              "POKE.method": "POST",
              "POKE.path": "/poke",
              "POKE.params": "thing,who,at",
              "POKE.body": "thing,who,at",
              "POKE.param.who.source": "runtime:identity",
              "POKE.param.at.source": "runtime:clock",
              "POKE.param.thing.source": "model",
              "POKE.param.thing.domain": "text:64",
              "POKE.landed": "status:200",
              "POKE.refused": "status:409",
              "IDLE.kind": "noop"
            }
            """;

    @BeforeAll
    static void boot() throws Exception {
        Db.bootstrap();
        slow = HttpServer.create(new InetSocketAddress(SLOW_PORT), 0);
        slow.createContext("/poke", ex -> {
            received.incrementAndGet();
            CountDownLatch hold = holdResponse;
            if (hold != null) {
                // the request WAS received and applied; the answer is what is lost
                try { hold.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            respond(ex, 200, "{\"ok\":true}");
        });
        slow.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        slow.start();
    }

    @AfterAll
    static void stop() { if (slow != null) slow.stop(0); }

    @BeforeEach
    void reset() throws Exception {
        received.set(0);
        holdResponse = null;
        try (Connection c = Db.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE platform_calls, decisions, holds, entries, transactions, accounts, cassettes, runs RESTART IDENTITY CASCADE");
        }
    }

    private static Manifest manifest() { return Manifest.of(String.format(MANIFEST, SLOW_PORT)); }

    /**
     * LESSON 1 · REPLAYING A WINDOW SENDS NOTHING NEW.
     *
     * minimart's seeded population derives every order id from its position in
     * the run, which is why replaying a window creates nothing rather than
     * duplicating everything. Putting a model in the loop threatens that,
     * because the model's answer varies between runs and the obvious
     * implementation lets its words reach the key.
     *
     * The separation that saves it: the model chooses WHAT to do and never the
     * identity of the doing. Here the same position is executed five times with
     * a DIFFERENT model answer each time, and the platform is touched once.
     */
    @Test
    void lesson1_the_same_position_executes_once_however_the_model_answers() throws Exception {
        Manifest m = manifest();
        UUID run = SeamLessonTest.newRun();
        Executor exec = new Executor(m, run);
        Memo memo = new Memo();

        for (int i = 0; i < 5; i++) {
            // a different decision every time, as a non-deterministic model gives
            var parsed = Grammar.parse("POKE thing=answer-" + i, m, memo::has, 700);
            assertTrue(parsed.ok());
            exec.run(m.cap("POKE"), parsed.intent(),
                    SeamLessonTest.decision(run, 3, 1, 0, "POKE"), 3, 1, 0, T0);
        }

        assertEquals(1, received.get(),
                "FIVE different model answers at one position, and the platform was touched once");
        assertEquals(1, callCount(run), "and exactly one call was recorded");
        System.out.println("lesson 1: 5 varying model answers at one position -> 1 request, 1 row");
    }

    /**
     * LESSON 2 · THE INTENTION IS COMMITTED BEFORE THE REQUEST LEAVES.
     *
     * The assertion that makes this real is reading the row from a SEPARATE
     * connection WHILE the request is still in flight. An implementation that
     * writes the row after the response arrives passes every other test in this
     * file and fails this one, and it is the implementation almost everybody
     * writes first.
     */
    @Test
    void lesson2_a_pending_row_exists_while_the_request_is_still_in_flight() throws Exception {
        Manifest m = manifest();
        UUID run = SeamLessonTest.newRun();
        Executor exec = new Executor(m, run);
        Memo memo = new Memo();
        long decisionId = SeamLessonTest.decision(run, 4, 0, 0, "POKE");

        holdResponse = new CountDownLatch(1);
        Thread caller = Thread.ofVirtual().start(() -> {
            try {
                exec.run(m.cap("POKE"), Grammar.parse("POKE thing=x", m, memo::has, 700).intent(),
                        decisionId, 4, 0, 0, T0);
            } catch (Exception ignored) { }
        });

        // wait until the server has it and is deliberately not answering
        long deadline = System.currentTimeMillis() + 5000;
        while (received.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertEquals(1, received.get(), "the platform has the request");

        // and from a completely separate connection, the claim is already there
        assertEquals(1, Calls.pending(run),
                "THE ROW EXISTS WHILE THE ANSWER DOES NOT, which is what a crash here would leave behind");

        holdResponse.countDown();
        caller.join(5000);
        assertEquals(0, Calls.pending(run), "and it settles once the answer arrives");
        System.out.println("lesson 2: a PENDING row was visible from another connection mid-flight");
    }

    /**
     * LESSON 3 · A LOST ANSWER IS PENDING, NOT AN ERROR.
     *
     * A read timeout means the request may well have been received, executed
     * and committed while the response was lost coming back. Recording that as
     * ERROR asserts knowledge nobody has, and every number computed from it
     * inherits the false confidence.
     *
     * Here the platform genuinely receives and applies the request, and simply
     * never answers in time. The honest record is PENDING: a question left
     * open, not an answer invented.
     */
    @Test
    void lesson3_a_timeout_is_recorded_as_unknown_rather_than_as_failed() throws Exception {
        Manifest m = manifest();
        UUID run = SeamLessonTest.newRun();
        Executor exec = new Executor(m, run);
        Memo memo = new Memo();

        holdResponse = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
            holdResponse.countDown();   // the platform answers, far too late
        });

        Executor.Result r = exec.run(m.cap("POKE"),
                Grammar.parse("POKE thing=y", m, memo::has, 700).intent(),
                SeamLessonTest.decision(run, 5, 0, 0, "POKE"), 5, 0, 0, T0);

        assertEquals("PENDING", r.outcome(),
                "the request was applied and the answer was lost, so PENDING is the only true thing to write");
        assertTrue(r.reason().contains("may have been applied"), r.reason());
        assertEquals(1, received.get(), "and it really was received, which is exactly why ERROR would be a lie");
        System.out.println("lesson 3: a lost answer recorded as PENDING, not ERROR: " + r.reason());
    }

    /**
     * LESSON 4 · THE RUN ID IS INSIDE THE KEY, AND THE DATABASE INSISTS.
     *
     * The derived key looks obviously correct and has one plausible way to be
     * wrong: dropping the run id. Everything still works, every test passes,
     * and two runs of the same experiment silently collide, with the second one
     * sending nothing and reporting a suspiciously clean sheet.
     *
     * The unique index is what makes that impossible rather than unlikely.
     */
    @Test
    void lesson4_two_runs_at_the_same_position_do_not_collide() throws Exception {
        Manifest m = manifest();
        Memo memo = new Memo();
        UUID runA = SeamLessonTest.newRun(), runB = SeamLessonTest.newRun();

        assertNotEquals(Keys.idem(runA, 0, 0, 0), Keys.idem(runB, 0, 0, 0),
                "the run is part of the key, or two experiments are one");

        new Executor(m, runA).run(m.cap("POKE"), Grammar.parse("POKE thing=a", m, memo::has, 700).intent(),
                SeamLessonTest.decision(runA, 0, 0, 0, "POKE"), 0, 0, 0, T0);
        new Executor(m, runB).run(m.cap("POKE"), Grammar.parse("POKE thing=b", m, memo::has, 700).intent(),
                SeamLessonTest.decision(runB, 0, 0, 0, "POKE"), 0, 0, 0, T0);

        assertEquals(2, received.get(), "the same position in two runs is two calls, not one");
        assertEquals(1, callCount(runA));
        assertEquals(1, callCount(runB));
        System.out.println("lesson 4: same position, two runs, two calls, no collision");
    }

    /**
     * LESSON 5 · EVERY INTENT EITHER LANDED OR IS ACCOUNTED FOR.
     *
     * The completeness question, asked the way minianalytics asks it: not "did
     * anything go wrong" but "is there anything I cannot account for". A
     * decision that formed an executable intent and has no call, and a call
     * with no outcome, are both holes, and both are found by a query rather
     * than noticed by somebody.
     */
    @Test
    void lesson5_no_executable_intent_disappears_without_a_record() throws Exception {
        Manifest m = manifest();
        UUID run = SeamLessonTest.newRun();
        Executor exec = new Executor(m, run);
        Memo memo = new Memo();

        for (int step = 0; step < 4; step++) {
            long d = SeamLessonTest.decision(run, 6, 0, step, "POKE");
            exec.run(m.cap("POKE"), Grammar.parse("POKE thing=s" + step, m, memo::has, 700).intent(),
                    d, 6, 0, step, T0);
        }
        // a WAIT decision, which legitimately has no call at all
        SeamLessonTest.decision(run, 6, 0, 9, "IDLE");

        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT d.intent, COUNT(p.id)
                       FROM decisions d LEFT JOIN platform_calls p ON p.decision_id = d.id
                      WHERE d.run_id = ?
                      GROUP BY d.id, d.intent""")) {
            ps.setObject(1, run);
            try (ResultSet rs = ps.executeQuery()) {
                int checked = 0;
                while (rs.next()) {
                    String intent = rs.getString(1);
                    long calls = rs.getLong(2);
                    if ("IDLE".equals(intent)) {
                        assertEquals(0, calls, "a no-op intent must have no call, or the audit means nothing");
                    } else {
                        assertEquals(1, calls, intent + " formed an intent and must have exactly one call");
                    }
                    checked++;
                }
                assertEquals(5, checked, "every decision was accounted for, in both directions");
            }
        }
        assertEquals(0, unsettled(run), "and no call was left without an outcome");
        System.out.println("lesson 5: 4 acting decisions with one call each, 1 no-op with none, nothing unaccounted");
    }

    // ------------------------------------------------------------------ helpers

    private static long callCount(UUID run) throws Exception {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM platform_calls WHERE run_id = ?")) {
            ps.setObject(1, run);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    private static long unsettled(UUID run) throws Exception {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM platform_calls WHERE run_id = ? AND outcome = 'PENDING'")) {
            ps.setObject(1, run);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
