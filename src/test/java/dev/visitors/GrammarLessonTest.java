package dev.visitors;

import dev.visitors.core.Db;
import dev.visitors.intent.Grammar;
import dev.visitors.intent.Parsed;
import dev.visitors.intent.Reject;
import dev.visitors.platform.Manifest;
import dev.visitors.run.Memo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A MODEL PROPOSES. IT DOES NOT ACT.
 *
 * Everything a language model produces is untrusted input, and the mistake that
 * makes an agent dangerous is treating a plausible sentence as an instruction.
 * So output is parsed into a closed alphabet the manifest declares, every
 * parameter is checked against a declared domain, and only then does anything
 * exist that could be executed.
 *
 * The rejection reasons are a taxonomy rather than one INVALID bucket, and that
 * is the point of most of these lessons. UNPARSEABLE says the model does not
 * understand the format, which is a prompt problem. PARAM_UNKNOWN_REF says it
 * understood perfectly and invented a product, which is a grounding problem.
 * One number covering both would make a prompt fix and a grounding fix
 * indistinguishable in the results, so the two would be tried at random.
 */
class GrammarLessonTest {

    static Manifest m;

    @BeforeAll
    static void load() throws Exception {
        Db.bootstrap();
        m = Manifest.load("/platforms/minimart.json");
    }

    /** A visitor that has browsed and seen exactly one product. */
    private static Memo memoWith(String... productIds) {
        Memo memo = new Memo();
        StringBuilder body = new StringBuilder("[");
        for (int i = 0; i < productIds.length; i++) {
            if (i > 0) body.append(',');
            body.append("{\"id\":\"").append(productIds[i]).append("\"}");
        }
        memo.absorb(m.cap("BROWSE"), body.append(']').toString());
        return memo;
    }

    /**
     * LESSON 1 · A MODEL MAY NOT NAME SOMETHING IT HAS NEVER SEEN.
     *
     * The sharpest gate in the system, and the one that makes "a visitor sees
     * HTTP responses and nothing else" a mechanism rather than a principle. A
     * model asked to subscribe will happily invent a product that sounds
     * exactly like a real one. Without this, that invention reaches the
     * platform, fails there, and is recorded as a platform problem.
     *
     * Note which reject it earns. PARAM_UNKNOWN_REF, not PARAM_TYPE: the model
     * formatted its answer perfectly and simply made the product up, and those
     * two failures are fixed in completely different ways.
     */
    @Test
    void lesson1_an_invented_product_never_reaches_the_platform() {
        Memo seen = memoWith("v-recovery-30");

        Parsed real = Grammar.parse("SUBSCRIBE variant=v-recovery-30 interval_days=30", m, seen::has, 900001);
        assertTrue(real.ok(), "a product it actually saw is fine");

        Parsed invented = Grammar.parse("SUBSCRIBE variant=v-premium-90 interval_days=30", m, seen::has, 900001);
        assertFalse(invented.ok(), "a product it never saw must not be executable");
        assertEquals(Reject.PARAM_UNKNOWN_REF, invented.reject(),
                "and the reason says GROUNDING, not formatting: the model wrote perfect syntax and made it up");
        System.out.println("lesson 1: an invented product is rejected as PARAM_UNKNOWN_REF before any request exists");
    }

    /**
     * LESSON 2 · THE MODEL MAY NOT SET THE CLOCK OR ITS OWN IDENTITY.
     *
     * Two values the runtime owns absolutely. A model that could set
     * `business_at` could move business time, and a run whose clock moved for
     * reasons nobody recorded is not a run, it is an anecdote. A model that
     * could set `customer` could act as somebody else, on a platform where
     * subscribing is idempotent per customer.
     *
     * Both are refused as PARAM_EXTRA rather than quietly ignored, because
     * "the model tried to forge the clock" is worth knowing.
     */
    @Test
    void lesson2_the_model_cannot_forge_the_clock_or_the_identity() {
        Memo seen = memoWith("v-recovery-30");

        Parsed clock = Grammar.parse(
                "SUBSCRIBE variant=v-recovery-30 interval_days=30 business_at=2030-01-01T00:00:00Z",
                m, seen::has, 900001);
        assertEquals(Reject.PARAM_EXTRA, clock.reject(), "the clock is not the model's to set");

        Parsed identity = Grammar.parse(
                "SUBSCRIBE variant=v-recovery-30 interval_days=30 customer=1000", m, seen::has, 900001);
        assertEquals(Reject.PARAM_EXTRA, identity.reject(), "nor is who it is");

        // and the runtime fills both itself
        Parsed good = Grammar.parse("SUBSCRIBE variant=v-recovery-30 interval_days=30", m, seen::has, 900001);
        assertEquals("900001", good.intent().params().get("customer"),
                "identity comes from the runtime, offset by the manifest so it cannot collide with anybody else");
        System.out.println("lesson 2: business_at and customer are refused from the model and supplied by the runtime");
    }

    /**
     * LESSON 3 · THE WRONG SHAPE AND THE WRONG VALUE ARE DIFFERENT FAILURES.
     *
     * A model that writes "thirty" where a number belongs cannot count on the
     * format. A model that writes 3650 where 7 to 365 is allowed can format
     * perfectly and cannot judge. Same field, opposite fixes, so one bucket for
     * both would hide which one is happening.
     */
    @Test
    void lesson3_a_type_error_and_a_range_error_are_told_apart() {
        Memo seen = memoWith("v-recovery-30");

        assertEquals(Reject.PARAM_TYPE,
                Grammar.parse("SUBSCRIBE variant=v-recovery-30 interval_days=thirty", m, seen::has, 900001).reject(),
                "letters where an integer goes is a formatting failure");
        assertEquals(Reject.PARAM_RANGE,
                Grammar.parse("SUBSCRIBE variant=v-recovery-30 interval_days=3650", m, seen::has, 900001).reject(),
                "a decade-long billing interval is a judgement failure, and it formatted fine");
        assertEquals(Reject.MISSING_PARAM,
                Grammar.parse("SUBSCRIBE variant=v-recovery-30", m, seen::has, 900001).reject(),
                "and an absent one is neither");
        System.out.println("lesson 3: TYPE, RANGE and MISSING are three different facts about the model");
    }

    /**
     * LESSON 4 · A MODEL THAT EXPLAINS ITSELF IS BEHAVING NORMALLY.
     *
     * Models narrate. A parser that demanded the whole response be exactly one
     * line would reject a large fraction of perfectly good decisions and report
     * an invalid rate that is really a measure of its own strictness. So prose
     * before and after the action line is ignored, and only the absence of any
     * recognisable line is a rejection.
     *
     * The second half is the distinction worth having: producing no intent at
     * all, and producing something that is clearly meant to be an intent but is
     * not in the alphabet, are different problems.
     */
    @Test
    void lesson4_prose_around_the_action_is_tolerated_but_invention_is_not() {
        Memo seen = memoWith("v-recovery-30");

        Parsed narrated = Grammar.parse("""
                Looking at what is available, the recovery stack fits this budget.
                SUBSCRIBE variant=v-recovery-30 interval_days=30
                That should be everything.""", m, seen::has, 900001);
        assertTrue(narrated.ok(), "a model that thinks out loud is not a model that failed");
        assertEquals("SUBSCRIBE", narrated.intent().name());

        assertEquals(Reject.UNPARSEABLE,
                Grammar.parse("I am not sure what to do here.", m, seen::has, 900001).reject(),
                "nothing resembling an intent at all");
        assertEquals(Reject.UNKNOWN_INTENT,
                Grammar.parse("REFUND order=1", m, seen::has, 900001).reject(),
                "clearly meant as an intent, and not one this platform offers");
        System.out.println("lesson 4: narration tolerated; nothing-at-all and invented-verb told apart");
    }

    /**
     * LESSON 5 · THE ALPHABET IS THE MANIFEST'S, NOT THE CORE'S.
     *
     * If the intent names were a Java enum, adding a platform would mean
     * editing it, and the headline claim of this slice would be false. This
     * asserts the negative directly: an intent minimart declares is unknown to
     * a manifest that does not, and vice versa, with no code in between
     * knowing either name.
     */
    @Test
    void lesson5_an_intent_valid_on_one_platform_is_unknown_on_another() {
        Manifest other = Manifest.of("""
                {
                  "platform.id": "other",
                  "platform.base_url": "http://localhost:1",
                  "platform.connect_timeout_ms": "1000",
                  "platform.read_timeout_ms": "1000",
                  "platform.identity_base": "1",
                  "alphabet": "PING,IDLE",
                  "fallback.intent": "IDLE",
                  "clock.kind": "noop",
                  "PING.kind": "call",
                  "PING.method": "GET",
                  "PING.path": "/ping",
                  "PING.params": "",
                  "PING.body": "",
                  "PING.landed": "status:200",
                  "IDLE.kind": "noop"
                }
                """);
        Memo seen = memoWith("v-recovery-30");

        assertEquals(Reject.UNKNOWN_INTENT,
                Grammar.parse("SUBSCRIBE variant=v-recovery-30 interval_days=30", other, seen::has, 1).reject(),
                "an intent from the other platform means nothing here");
        assertTrue(Grammar.parse("PING", other, seen::has, 1).ok());
        assertEquals(Reject.UNKNOWN_INTENT,
                Grammar.parse("PING", m, seen::has, 900001).reject(),
                "and the reverse, which is only possible because neither name is in the core");
        System.out.println("lesson 5: the alphabet travels with the manifest, not with the code");
    }
}
