package dev.visitors;

import dev.visitors.platform.Manifest;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A TEST THAT SKIPS ITSELF MUST NOT BE ABLE TO DO IT QUIETLY.
 *
 * This class exists because of a defect in the test suite rather than in the
 * system, which is the kind that is hardest to notice and does the most damage.
 *
 * MinimartTaskLessonTest is the only place anything in this repository touches a
 * real platform. It defaulted to a port that had been used to run a local
 * instance during development, and which nothing in either repository actually
 * listens on. The result was not a red build. Its reachability probe swallowed
 * the ConnectException, the JUnit assumption aborted the container, and surefire
 * printed "Tests run: 0" inside a BUILD SUCCESS. Every claim about a visitor
 * browsing, subscribing, verifying and cancelling was unproven, and the Executor
 * could have been deleted without turning anything red.
 *
 * A skip is a legitimate thing for a test that needs a neighbouring service. A
 * skip that nobody can see is not. These lessons make the conditions for
 * skipping themselves testable, so the escape hatch cannot quietly widen.
 */
class SkipIsLoudLessonTest {

    /**
     * LESSON 1 · THE TEST AND THE MANIFEST POINT AT THE SAME PLACE.
     *
     * The only way the port could drift is if the default were written down
     * twice. It is now written once, in the manifest, and this asserts that the
     * test reads it from there rather than repeating it.
     */
    @Test
    void lesson1_the_real_platform_test_defaults_to_the_shipped_address() throws Exception {
        String shipped = MinimartTaskLessonTest.shippedBaseUrl();
        assertEquals(shipped, System.getenv().getOrDefault("MINIMART_URL", shipped),
                "the default address must come from the manifest, not from a literal in the test");

        // and the manifest's address must be a real, parseable http address
        URI uri = URI.create(shipped);
        assertEquals("http", uri.getScheme(), "the manifest declares a usable base URL: " + shipped);
        assertTrue(uri.getPort() > 0, "with a port: " + shipped);
        System.out.println("lesson 1: the real-platform test and the manifest agree on " + shipped);
    }

    /**
     * LESSON 2 · ONLY ONE CLASS IS ALLOWED TO SKIP ITSELF.
     *
     * An assumption is the right tool for a test that genuinely needs another
     * service running, and the wrong tool for anything else, because it turns a
     * failure into a silence. If assumptions spread, the suite slowly stops
     * asserting anything while continuing to look green.
     *
     * So the number of places allowed to opt out is fixed here, deliberately as
     * a bare number that has to be edited on purpose. Editing it is the moment
     * somebody has to justify the new escape hatch.
     */
    @Test
    void lesson2_no_other_test_may_quietly_opt_out() throws Exception {
        // built rather than written, so this file does not match its own scan
        List<String> optOuts = List.of("Assumptions" + ".assume", "assume" + "True", "@" + "Disabled");
        String self = "SkipIsLoudLessonTest.java";

        List<String> skippers = new java.util.ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/test/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (f.getFileName().toString().equals(self)) continue;
                String text = Files.readString(f);
                if (optOuts.stream().anyMatch(text::contains)) skippers.add(f.getFileName().toString());
            }
        }
        assertEquals(List.of("MinimartTaskLessonTest.java"), skippers,
                "exactly one class may skip itself, and only because it needs a real platform: " + skippers);
        System.out.println("lesson 2: one class may opt out, and it is the one that needs a neighbour running");
    }

    /**
     * LESSON 3 · THE MANIFEST THIS REPO SHIPS ACTUALLY LOADS.
     *
     * Cheap, and it closes the gap between "the seam lessons pass against
     * manifests written inside tests" and "the manifest we actually ship is
     * valid". A test-authored manifest proves the loader; only this proves the
     * file.
     */
    @Test
    void lesson3_the_shipped_manifest_loads_and_declares_what_it_claims() throws Exception {
        Manifest m = Manifest.load("/platforms/minimart.json");

        assertFalse(m.alphabet().isEmpty(), "an alphabet");
        assertNotNull(m.cap(m.fallbackIntent()), "a fallback intent that exists in it");
        for (String intent : m.alphabet()) {
            assertNotNull(m.cap(intent), intent + " is declared in the alphabet and must be defined");
        }
        assertTrue(m.identityBase() > 0, "an identity base, so visitors cannot collide with other populations");
        assertNotNull(m.clock(), "and a clock, because business time is never the wall clock");

        // the tool text handed to a model must be stable, since the cassette
        // key is a hash of the prompt it goes into
        assertEquals(m.toolLines(), m.toolLines(), "rendered twice, identical");
        assertEquals(m.sha12(), Manifest.load("/platforms/minimart.json").sha12(),
                "and the digest is stable, so a run can record which version drove it");
        System.out.println("lesson 3: the shipped manifest loads, and its digest is " + m.sha12());
    }

    /**
     * LESSON 4 · THE CODEC CAN READ A JSON BOOLEAN.
     *
     * Found by review, and it was load-bearing in a way that would have been
     * baffling to debug. The scanner accepted only numeric characters after a
     * colon, so every boolean read back as null. The shipped manifest's own
     * precheck is `body.eq:running=false` against an endpoint that answers
     * `"running":false`, so that rule could never match: the check meant to
     * refuse a run when somebody else owns the clock would have refused every
     * run instead, and nothing in the message would have said why.
     *
     * The general lesson is about the shape of the bug rather than the bug. A
     * hand-written scanner is a fine thing to have, and the moment to find out
     * what it cannot read is not when a rule silently stops matching.
     */
    @Test
    void lesson4_the_scanner_reads_the_values_the_rules_depend_on() {
        String body = "{\"running\":false,\"message\":\"idle\",\"count\":3,\"done\":true,\"next\":null}";

        assertEquals("false", dev.visitors.core.Json.str(body, "running"),
                "a boolean, which the first version returned as null");
        assertEquals("true", dev.visitors.core.Json.str(body, "done"));
        assertEquals("null", dev.visitors.core.Json.str(body, "next"));
        assertEquals("3", dev.visitors.core.Json.str(body, "count"), "numbers still work");
        assertEquals("idle", dev.visitors.core.Json.str(body, "message"), "and strings");

        // the rule that actually depends on it
        assertTrue(dev.visitors.platform.Rule.parse("body.eq:running=false").test(200, body),
                "THE PRECHECK RULE MATCHES, which it could not do while booleans read as null");
        assertFalse(dev.visitors.platform.Rule.parse("body.eq:running=true").test(200, body));
        System.out.println("lesson 4: booleans, null and numbers all read, so body.eq rules work");
    }
}
