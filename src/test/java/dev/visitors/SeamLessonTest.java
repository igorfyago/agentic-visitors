package dev.visitors;

import com.sun.net.httpserver.HttpServer;
import dev.visitors.core.Db;
import dev.visitors.intent.Grammar;
import dev.visitors.intent.Parsed;
import dev.visitors.platform.Manifest;
import dev.visitors.run.Executor;
import dev.visitors.run.Memo;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IS THE DECOUPLING REAL, OR JUST TIDY?
 *
 * "Adding a platform is a manifest file and zero changes to the core" is easy
 * to claim and easy to fake, and the usual fake is subtle: the manifest names
 * the endpoints while the core still knows that success means 200, that there
 * is such a thing as a catalogue, and that the tenant is called helix. Every
 * one of those is invisible until a second platform arrives, at which point all
 * of them are core edits.
 *
 * So this suite tries to PROVE the claim false, two ways.
 *
 * Lesson 1 boots a platform that is deliberately nothing like minimart: it
 * answers 201 for success, puts its errors in a different field, and names its
 * resources differently. It is described only by a manifest written inside the
 * test. If any minimart convention had leaked into the core, this cannot pass.
 *
 * Lesson 2 scans every compiled source file in the core for platform
 * vocabulary. It catches the leak lesson 1 cannot: a convenience constant that
 * happens to agree with the manifest, which works perfectly until the two
 * disagree.
 */
class SeamLessonTest {

    static final Instant T0 = Instant.parse("2027-02-01T00:00:00Z");
    static HttpServer library;
    static final int LIB_PORT = 18190;
    static final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeAll
    static void boot() throws Exception {
        Db.bootstrap();
        // A LENDING LIBRARY. It shares no convention with minimart on purpose:
        // 201 for a created loan, errors under "problem" rather than "error",
        // and a 403 that genuinely means refused, which minimart cannot express.
        library = HttpServer.create(new InetSocketAddress(LIB_PORT), 0);
        library.createContext("/shelf", ex -> respond(ex, 200,
                "[{\"isbn\":\"978-0" + "\",\"title\":\"A\"},{\"isbn\":\"978-1\",\"title\":\"B\"}]"));
        library.createContext("/loans", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastBody.set(body);
            if (body.contains("978-1")) {
                // a refusal the platform is able to express, unlike minimart
                respond(ex, 403, "{\"problem\":\"already on loan\"}");
            } else {
                respond(ex, 201, "{\"loan\":\"L-1\"}");
            }
        });
        library.start();
    }

    @AfterAll
    static void stop() { if (library != null) library.stop(0); }

    @BeforeEach
    void reset() throws Exception {
        try (Connection c = Db.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE platform_calls, decisions, holds, entries, transactions, accounts, cassettes, runs RESTART IDENTITY CASCADE");
        }
    }

    private static final String LIBRARY_MANIFEST = """
            {
              "platform.id": "lending-library",
              "platform.base_url": "http://localhost:%d",
              "platform.connect_timeout_ms": "2000",
              "platform.read_timeout_ms": "5000",
              "platform.identity_base": "500",
              "alphabet": "LIST,BORROW,IDLE",
              "fallback.intent": "IDLE",
              "clock.kind": "noop",
              "LIST.kind": "call",
              "LIST.method": "GET",
              "LIST.path": "/shelf",
              "LIST.params": "",
              "LIST.body": "",
              "LIST.landed": "status:200 & !body.has:problem",
              "LIST.refused": "status:403",
              "LIST.capture_each.books": "isbn",
              "BORROW.kind": "call",
              "BORROW.method": "POST",
              "BORROW.path": "/loans",
              "BORROW.params": "isbn,member,days",
              "BORROW.body": "isbn,member,days",
              "BORROW.param.member.source": "runtime:identity",
              "BORROW.param.isbn.source": "model",
              "BORROW.param.isbn.domain": "set:books",
              "BORROW.param.days.source": "model",
              "BORROW.param.days.domain": "int:1..28",
              "BORROW.landed": "status:201",
              "BORROW.refused": "status:403 | (status:200 & body.has:problem)",
              "IDLE.kind": "noop"
            }
            """;

    /**
     * LESSON 1 · A PLATFORM THE CORE HAS NEVER HEARD OF, DRIVEN BY A FILE.
     *
     * Nothing about this library resembles minimart. Success is 201, not 200.
     * Errors live under "problem", not "error". Its resources are books with
     * ISBNs, and it can refuse, which minimart cannot. The core is given
     * nothing but the manifest.
     *
     * The assertion that matters is the 201. If the core had learned anywhere
     * that success means 200, a created loan would be classified ERROR and this
     * fails. That is the difference between decoupling and an untested claim.
     */
    @Test
    void lesson1_a_second_platform_with_incompatible_conventions_needs_no_core_change() throws Exception {
        Manifest lib = Manifest.of(String.format(LIBRARY_MANIFEST, LIB_PORT));
        UUID run = newRun();
        Executor exec = new Executor(lib, run);
        Memo memo = new Memo();

        // the visitor learns what exists, from a response and nowhere else
        long listDecision = decision(run, 0, 0, 0, "LIST");
        Executor.Result listed = exec.run(lib.cap("LIST"),
                Grammar.parse("LIST", lib, memo::has, 500).intent(), listDecision, 0, 0, 0, T0);
        assertEquals("LANDED", listed.outcome(), "200 is this platform's success for a read");
        memo.absorb(lib.cap("LIST"), listed.body());
        assertEquals(List.of("978-0", "978-1"), memo.set("books"),
                "both books, which needs the array reader the flat scanner did not have");

        // borrow one. The platform answers 201, a status minimart never uses.
        Parsed borrow = Grammar.parse("BORROW isbn=978-0 days=14", lib, memo::has, 500);
        assertTrue(borrow.ok(), "a valid proposal against a manifest the core has never seen");
        Executor.Result got = exec.run(lib.cap("BORROW"), borrow.intent(),
                decision(run, 0, 0, 1, "BORROW"), 0, 0, 1, T0);

        assertEquals(201, got.status());
        assertEquals("LANDED", got.outcome(),
                "201 IS SUCCESS HERE, and the core knew that only because the file said so");
        assertTrue(lastBody.get().contains("\"member\":\"500\""),
                "identity came from the manifest's own base, not a constant: " + lastBody.get());

        // and a refusal this platform CAN express, which minimart cannot
        Parsed refused = Grammar.parse("BORROW isbn=978-1 days=7", lib, memo::has, 500);
        Executor.Result no = exec.run(lib.cap("BORROW"), refused.intent(),
                decision(run, 0, 0, 2, "BORROW"), 0, 0, 2, T0);
        assertEquals("REFUSED", no.outcome(),
                "the world said no, which is not an error and must not be recorded as one");
        System.out.println("lesson 1: a 201-means-created, 403-means-refused platform driven with zero core changes");
    }

    /**
     * LESSON 2 · NO PLATFORM VOCABULARY IN THE CORE, CHECKED BY READING IT.
     *
     * The leak lesson 1 cannot catch: a constant in the core that happens to
     * agree with the manifest. Everything works, the second platform passes,
     * and the two only ever disagree in production.
     *
     * The scan covers EVERY source file under dev/visitors, with no carve-out.
     * A seam test with an exemption around the package where the leak lives is
     * not a seam test.
     */
    @Test
    void lesson2_no_minimart_vocabulary_appears_anywhere_in_the_core() throws Exception {
        List<String> banned = List.of("helix", "MAD", "minimart", "subscription", "variant",
                "8081", "catalog", "tenant");
        Path core = Path.of("src/main/java/dev/visitors");

        StringBuilder leaks = new StringBuilder();
        try (Stream<Path> files = Files.walk(core)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(f);
                // comments are allowed to discuss the world; code is not
                String code = stripComments(text);
                for (String word : banned) {
                    if (code.toLowerCase().contains(word.toLowerCase())) {
                        leaks.append(f.getFileName()).append(" contains '").append(word).append("'\n");
                    }
                }
            }
        }
        assertEquals("", leaks.toString(),
                "platform vocabulary leaked into the core:\n" + leaks);
        System.out.println("lesson 2: no platform vocabulary in any of dev/visitors, comments excepted");
    }

    /**
     * LESSON 3 · A MANIFEST THAT LIES ABOUT ITSELF FAILS AT LOAD.
     *
     * A manifest is configuration, and configuration errors have a habit of
     * surfacing at tick 40 of a paid run rather than at startup. Every
     * capability is resolved and every rule parsed while loading, so a typo
     * costs a startup rather than an experiment.
     */
    @Test
    void lesson3_a_broken_manifest_is_refused_at_load_not_at_tick_forty() {
        String missingMethod = LIBRARY_MANIFEST.replace("\"BORROW.method\": \"POST\",", "");
        assertNotEquals(LIBRARY_MANIFEST, missingMethod, "the test must actually remove the key it claims to");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Manifest.of(String.format(missingMethod, LIB_PORT)));
        assertTrue(e.getMessage().contains("BORROW.method"), "and it names the missing key: " + e.getMessage());

        String badRule = LIBRARY_MANIFEST.replace("\"BORROW.landed\": \"status:201\"",
                "\"BORROW.landed\": \"gibberish:201\"");
        IllegalArgumentException r = assertThrows(IllegalArgumentException.class,
                () -> Manifest.of(String.format(badRule, LIB_PORT)));
        assertTrue(r.getMessage().contains("gibberish"), "and names the bad operator: " + r.getMessage());

        String bodyNotDeclared = LIBRARY_MANIFEST.replace("\"BORROW.body\": \"isbn,member,days\"",
                "\"BORROW.body\": \"isbn,member,days,fine\"");
        IllegalArgumentException b = assertThrows(IllegalArgumentException.class,
                () -> Manifest.of(String.format(bodyNotDeclared, LIB_PORT)));
        assertTrue(b.getMessage().contains("fine"),
                "a body field that is not a declared param could never be filled: " + b.getMessage());
        System.out.println("lesson 3: a missing key, a bad rule and an undeclared body field all fail at load");
    }

    // ------------------------------------------------------------------ helpers

    private static String stripComments(String java) {
        return java.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    static UUID newRun() throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection c = Db.open();
             var ps = c.prepareStatement("""
                     INSERT INTO runs(id, mode, arm_fingerprint, model_id, model_version, template_id)
                     VALUES (?, 'SEEDED', 'seam-test', 'none', 'none', 'none')""")) {
            ps.setObject(1, id);
            ps.executeUpdate();
        }
        return id;
    }

    /**
     * Record a decision at a position, or find the one already there.
     *
     * DO NOTHING rather than DO UPDATE, deliberately. A replay must not
     * overwrite what was decided the first time: the original decision is the
     * fact, and quietly replacing it would make a replayed run look like it had
     * always chosen whatever the model happened to say on the second pass.
     */
    static long decision(UUID run, int visitor, int tick, int step, String intent) throws Exception {
        try (Connection c = Db.open();
             var ps = c.prepareStatement("""
                     INSERT INTO decisions(run_id, visitor_id, tick, step, intent, params, source, business_at)
                     VALUES (?,?,?,?,?, '{}', 'SEEDED', ?)
                     ON CONFLICT (run_id, visitor_id, tick, step) DO NOTHING
                     RETURNING id""")) {
            ps.setObject(1, run); ps.setInt(2, visitor); ps.setInt(3, tick); ps.setInt(4, step);
            ps.setString(5, intent); ps.setTimestamp(6, java.sql.Timestamp.from(T0));
            try (var rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        try (Connection c = Db.open();
             var ps = c.prepareStatement("""
                     SELECT id FROM decisions
                      WHERE run_id = ? AND visitor_id = ? AND tick = ? AND step = ?""")) {
            ps.setObject(1, run); ps.setInt(2, visitor); ps.setInt(3, tick); ps.setInt(4, step);
            try (var rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }
}
