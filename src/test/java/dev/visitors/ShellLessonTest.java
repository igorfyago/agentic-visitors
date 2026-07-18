package dev.visitors;

import dev.visitors.budget.Budget;
import dev.visitors.core.Db;
import dev.visitors.core.Ledger;
import dev.visitors.model.Cassettes;
import dev.visitors.model.Decider;
import dev.visitors.model.FakeModel;
import dev.visitors.model.Model;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE SHELL, PROVEN WITHOUT A LANGUAGE MODEL IN SIGHT.
 *
 * This service is not "a thing that calls an LLM". It is a containment layer
 * for a non-deterministic, priced, rate-limited, occasionally-wrong external
 * dependency, which happens to be a language model. That framing is worth
 * something only if it survives being tested, so the first slice is built and
 * proven against a fake model and spends nothing.
 *
 * The test of whether the framing was honest: swap the dependency for a
 * sanctions-screening or FX-rate vendor and every lesson below still applies.
 */
class ShellLessonTest {

    static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");

    /** A euro is a million micro-euros. Prices are per token. */
    static final Decider.Pricing PRICING =
            new Decider.Pricing(new BigDecimal("0.30"), new BigDecimal("1.50"));

    @BeforeAll
    static void migrate() throws Exception { Db.bootstrap(); }

    @BeforeEach
    void reset() throws Exception {
        try (Connection c = Db.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE platform_calls, decisions, holds, entries, transactions, accounts, cassettes, runs RESTART IDENTITY CASCADE");
        }
    }

    /**
     * LESSON 1 · A REPLAY IS BYTE-IDENTICAL, AND THE MODEL IS NOT CALLED.
     *
     * The same claim minipay makes about a retried payment, for the same
     * reason: a caller must not be able to tell a replay from the original. The
     * assertion that matters is the second one. Returning the right text while
     * secretly calling the model again would pass a naive test, cost money
     * every run, and quietly destroy reproducibility.
     */
    @Test
    void lesson1_a_recorded_decision_replays_free_and_identically() throws Exception {
        UUID run = newRun("LIVE");
        Budget.fund(run, new BigDecimal("1000000"), T0);
        FakeModel model = new FakeModel("fake-1", "2026-11-01",
                r -> new Model.Response("{\"intent\":\"SUBSCRIBE\",\"variant\":\"v-sleep-30\"}", 40, 12, 130));
        Decider live = new Decider(Decider.Mode.LIVE, model, PRICING, run);

        Model.Request request = request("decide-v1", "visitor 7 has 120 EUR and no subscription");
        Decider.Outcome first = live.decide(request, "BROWSE", T0);
        assertEquals(Decider.Source.MODEL, first.source());
        assertEquals(1, model.callCount());

        // the same decision point, in a fresh REPLAY run
        UUID replayRun = newRun("REPLAY");
        Decider replay = new Decider(Decider.Mode.REPLAY, model, PRICING, replayRun);
        Decider.Outcome second = replay.decide(request, "BROWSE", T0);

        assertEquals(first.text(), second.text(), "byte identical, or it is not a replay");
        assertEquals(Decider.Source.CASSETTE, second.source());
        assertEquals(1, model.callCount(), "THE MODEL WAS NOT CALLED AGAIN");
        assertEquals(0, second.microEur().signum(), "and it cost nothing");
        System.out.println("lesson 1: replay returned identical bytes, model call count still " + model.callCount());
    }

    /**
     * LESSON 2 · A CHANGED PROMPT IS A CONFLICT, NOT A QUIET DIFFERENCE.
     *
     * If the template or the way state is rendered changes, this run is no
     * longer comparable with the recorded one. The dangerous behaviour is not
     * failing, it is succeeding: silently calling the model and presenting the
     * result as the same experiment. The fingerprint makes the difference
     * detectable, and detecting it is the only useful response.
     */
    @Test
    void lesson2_a_prompt_that_changed_is_detected_rather_than_silently_rerun() throws Exception {
        UUID run = newRun("LIVE");
        Budget.fund(run, new BigDecimal("1000000"), T0);
        FakeModel model = new FakeModel("fake-1", "2026-11-01");
        Decider live = new Decider(Decider.Mode.LIVE, model, PRICING, run);
        live.decide(request("decide-v1", "visitor 7 has 120 EUR"), "BROWSE", T0);

        // the same fingerprint, forced against a different prompt: a collision
        // or a bug, and both are worth stopping for
        String fp = Cassettes.fingerprint("fake-1", "2026-11-01",
                request("decide-v1", "visitor 7 has 120 EUR"));
        assertThrows(Cassettes.Conflict.class,
                () -> Cassettes.find(fp, "visitor 7 has 999 EUR and a coupon"),
                "a fingerprint that no longer matches its prompt must not be served");

        // and a genuinely different prompt is simply a different key
        String other = Cassettes.fingerprint("fake-1", "2026-11-01",
                request("decide-v1", "visitor 7 has 999 EUR and a coupon"));
        assertNotEquals(fp, other, "different prompts, different cassettes");
        assertNull(Cassettes.find(other, "visitor 7 has 999 EUR and a coupon"));

        // a pinned version is part of the key, so a model swap is a new experiment
        String newVersion = Cassettes.fingerprint("fake-1", "2026-12-01",
                request("decide-v1", "visitor 7 has 120 EUR"));
        assertNotEquals(fp, newVersion,
                "a different model version is a different experiment, enforced by the key");
        System.out.println("lesson 2: prompt change detected as a conflict, version change is a different key");
    }

    /**
     * LESSON 3 · IN REPLAY, A MISS IS FATAL AND NEVER BECOMES A LIVE CALL.
     *
     * The most important rule in this service. Test infrastructure fails by
     * silently degrading into non-determinism: a missing cassette that quietly
     * escalates to a live call produces a run that LOOKS reproducible, is not,
     * and bills for the privilege. Exactly the shape of the connection-pool bug
     * in minimart, where the missing pool did not make things slow, it destroyed
     * determinism.
     */
    @Test
    void lesson3_replay_never_escalates_a_miss_into_a_live_call() throws Exception {
        UUID run = newRun("REPLAY");
        FakeModel model = new FakeModel("fake-1", "2026-11-01");
        Decider replay = new Decider(Decider.Mode.REPLAY, model, PRICING, run);

        assertThrows(Cassettes.Miss.class,
                () -> replay.decide(request("decide-v1", "nothing was ever recorded for this"), "BROWSE", T0),
                "a replay with no recording must fail the run");
        assertEquals(0, model.callCount(), "AND THE MODEL WAS NEVER REACHED");

        // SEEDED, by contrast, never wants a model and never misses
        Decider seeded = new Decider(Decider.Mode.SEEDED, model, PRICING, run);
        Decider.Outcome out = seeded.decide(request("decide-v1", "anything at all"), "BROWSE", T0);
        assertEquals("BROWSE", out.text());
        assertEquals(Decider.Source.SEEDED, out.source());
        assertEquals(0, model.callCount(), "CI runs here, and CI cannot spend money by construction");
        System.out.println("lesson 3: REPLAY miss threw and called nothing; SEEDED decided without a model");
    }

    /**
     * LESSON 4 · A FUNDED RUN CANNOT OVERSPEND, HOWEVER HARD IT TRIES.
     *
     * The third incarnation of one idea. minibank: you cannot overdraw.
     * minimart: you cannot oversell. Here: you cannot overspend. All three are
     * a non-negative CHECK on a balance under an ordered row lock, and none of
     * them is a counter somebody has to remember to increment.
     *
     * Fifty visitors race a budget that covers ten calls. This is the test that
     * would catch a check-then-act written outside the lock, which is the
     * natural way to write it and passes every sequential test.
     */
    @Test
    void lesson4_fifty_concurrent_visitors_cannot_spend_more_than_the_run_was_funded() throws Exception {
        UUID run = newRun("LIVE");
        // Every prompt is the SAME LENGTH, so every call authorises the same
        // upper bound and "funded for ten" is exact rather than approximate.
        // The first version varied the prompt length by visitor number, funded
        // ten of the shortest, and got nine: the guarantee held, the
        // arithmetic in the test did not. Worth keeping as a note, because a
        // budget expressed in calls rather than in money is always lying
        // slightly, and here it is the test that has to be honest about it.
        BigDecimal perCall = PRICING.upperBound(request("decide-v1", promptFor(0)));
        Budget.fund(run, perCall.multiply(BigDecimal.TEN), T0);

        FakeModel model = new FakeModel("fake-1", "2026-11-01",
                r -> new Model.Response("{\"intent\":\"BROWSE\"}", 10, 100, 50));
        AtomicInteger allowed = new AtomicInteger(), refused = new AtomicInteger();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                final int n = i;
                fs.add(pool.submit(() -> {
                    // a distinct prompt each, so nothing is served from a cassette
                    Decider d = new Decider(Decider.Mode.LIVE, model, PRICING, run);
                    try {
                        d.decide(request("decide-v1", promptFor(n)), "BROWSE", T0);
                        allowed.incrementAndGet();
                    } catch (Budget.Exhausted e) {
                        refused.incrementAndGet();
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get();
        }

        assertEquals(10, allowed.get(), "exactly the number the run was funded for");
        assertEquals(40, refused.get(), "and the rest were refused BEFORE the model was called");
        assertEquals(10, model.callCount(), "a refused call never reaches the vendor");
        assertTrue(Budget.available(run).signum() >= 0, "the balance never went negative");

        try (Connection c = Db.open()) {
            assertEquals(List.of(), Ledger.sumZeroViolations(c), "and every transaction still sums to zero");
        }
        System.out.println("lesson 4: 50 visitors, budget for 10 -> " + allowed.get() + " spent, "
                + refused.get() + " refused, balance " + Budget.available(run));
    }

    /**
     * LESSON 5 · A CALL THAT FAILS COSTS NOTHING, AND THE VISITOR CARRIES ON.
     *
     * Two claims, and the second is the one that makes this a simulation rather
     * than a demo. First: a hold that produced no response is voided in full,
     * because a compensation that restores approximately the right amount is a
     * slow leak that looks like nothing until a run dies short of its ticks.
     * Second: a visitor never blocks on a bad model. The seeded decision is the
     * floor, and the fact that it was used is RECORDED rather than hidden, since
     * a run that was mostly fallbacks is a different experiment from one where
     * the model actually answered.
     */
    @Test
    void lesson5_a_failed_call_is_voided_in_full_and_the_visitor_still_decides() throws Exception {
        UUID run = newRun("LIVE");
        BigDecimal funded = new BigDecimal("1000000");
        Budget.fund(run, funded, T0);

        FakeModel down = FakeModel.failing("fake-1", "2026-11-01", "connection reset");
        Decider live = new Decider(Decider.Mode.LIVE, down, PRICING, run);

        Decider.Outcome out = live.decide(request("decide-v1", "the model is down"), "BROWSE", T0);

        assertEquals("BROWSE", out.text(), "the visitor still has a decision");
        assertEquals(Decider.Source.SEEDED, out.source(), "and it is recorded as a fallback, not passed off as the model");
        assertEquals(0, funded.compareTo(Budget.available(run)),
                "the hold was voided IN FULL: a failed call costs exactly nothing");
        assertEquals(0, Budget.spent(run).signum(), "nothing was captured");
        assertEquals(0, Cassettes.count(), "and nothing was recorded, because nothing came back");

        try (Connection c = Db.open()) {
            assertEquals(List.of(), Ledger.sumZeroViolations(c), "the spend ledger still balances");
        }
        System.out.println("lesson 5: model down -> seeded fallback, budget restored exactly to " + Budget.available(run));
    }

    /**
     * LESSON 6 · A FAILURE TO BILL IS NOT A FAILURE TO CALL.
     *
     * This one is here because the concurrency lesson above found it, in code
     * written the obvious way. The first version wrapped the model call, the
     * capture and the recording in a single try, with a catch that voided the
     * hold and fell back. So when the CAPTURE threw, on a constraint that
     * refused to record a cost higher than the estimate, the system concluded
     * the model had failed: it voided the hold, returned the seeded answer, and
     * fifty calls that genuinely happened and would genuinely be invoiced were
     * recorded as costing nothing. The budget looked untouched. Lesson 4 failed
     * with 50 allowed instead of 10, which is the only reason anyone found out.
     *
     * Two rules came out of it. A response that arrived is BILLED, whatever goes
     * wrong afterwards, because the vendor has already been paid. And an
     * estimate that turns out too low is RECORDED as an overage rather than
     * refused, because a ledger that declines to write down a real cost makes
     * real spending look free, which is the one direction in which nobody
     * notices the error.
     */
    @Test
    void lesson6_a_call_that_cost_more_than_estimated_is_recorded_not_refused() throws Exception {
        UUID run = newRun("LIVE");
        BigDecimal funded = new BigDecimal("1000000");
        Budget.fund(run, funded, T0);

        // the response reports far more prompt tokens than the estimator guessed
        FakeModel greedy = new FakeModel("fake-1", "2026-11-01",
                r -> new Model.Response("{\"intent\":\"BROWSE\"}", 100_000, 100, 90));
        Decider live = new Decider(Decider.Mode.LIVE, greedy, PRICING, run);

        Model.Request request = request("decide-v1", "short prompt, expensive answer");
        BigDecimal authorized = PRICING.upperBound(request);
        BigDecimal actual = PRICING.actual(new Model.Response("", 100_000, 100, 0));
        assertTrue(actual.compareTo(authorized) > 0, "the premise: this call costs more than was held");

        Decider.Outcome out = live.decide(request, "BROWSE", T0);

        assertEquals(Decider.Source.MODEL, out.source(),
                "the model answered, so this is a model decision and not a fallback");
        assertEquals(1, greedy.callCount());
        assertEquals(0, actual.compareTo(Budget.spent(run)),
                "THE REAL COST IS ON THE BOOKS, not the estimate and not zero");
        assertEquals(1, Budget.overages(run),
                "and the fact that the estimate was too low is recorded, so it can be fixed");
        assertEquals(0, funded.subtract(actual).compareTo(Budget.available(run)),
                "the run paid exactly what the call cost");

        try (Connection c = Db.open()) {
            assertEquals(List.of(), Ledger.sumZeroViolations(c), "and the ledger still balances");
        }
        System.out.println("lesson 6: cost " + actual + " exceeded the hold " + authorized
                + " and was billed in full, with 1 overage recorded");
    }

    // ------------------------------------------------------------------ helpers

    /** Fixed width, so every visitor's prompt costs the same to authorise. */
    private static String promptFor(int visitor) {
        return "decide for visitor " + String.format("%02d", visitor);
    }

    private static Model.Request request(String template, String prompt) {
        return new Model.Request(template, prompt, 100, 0.0);
    }

    private static UUID newRun(String mode) throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection c = Db.open();
             var ps = c.prepareStatement("""
                     INSERT INTO runs(id, mode, arm_fingerprint, model_id, model_version, template_id)
                     VALUES (?,?,?,?,?,?)""")) {
            ps.setObject(1, id); ps.setString(2, mode);
            ps.setString(3, "fake-1/2026-11-01/decide-v1");
            ps.setString(4, "fake-1"); ps.setString(5, "2026-11-01"); ps.setString(6, "decide-v1");
            ps.executeUpdate();
        }
        return id;
    }
}
