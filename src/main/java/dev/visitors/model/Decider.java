package dev.visitors.model;

import dev.visitors.budget.Budget;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * THE SHELL · the containment layer this whole service exists to build.
 *
 * Between the agent loop and the model sit four things, and each of them is a
 * real problem that any metered, unreliable external dependency presents:
 *
 *   MODE      LIVE records, REPLAY replays and may never call out, SEEDED
 *             never involves a model at all. CI runs SEEDED or REPLAY, so CI
 *             cannot spend money by construction rather than by policy.
 *   CASSETTE  content-keyed, so a replay is byte-identical and a changed
 *             prompt is a detectable conflict instead of a silent difference.
 *   BUDGET    authorize before the call, capture the real cost after, void on
 *             failure. The ceiling is a ledger constraint, so no loop above
 *             this line can overspend.
 *   FALLBACK  a model that errors, times out or answers off-schema must not
 *             stop a visitor. The seeded decision takes over, and the fact that
 *             it did is recorded rather than hidden.
 */
public final class Decider {

    public enum Mode { LIVE, REPLAY, SEEDED }

    /** Where a decision came from. Recorded on every one, because a run whose
     *  decisions were mostly fallbacks is a different experiment from one where
     *  the model answered, and nothing downstream can tell without being told. */
    public enum Source { MODEL, CASSETTE, SEEDED, INVALID }

    public record Outcome(String text, Source source, String fingerprint,
                          BigDecimal microEur, int latencyMs) {}

    /** Cost per token, in micro-euros. Authorisation uses maxTokens because the
     *  output length is unknowable until the response exists. */
    public record Pricing(BigDecimal perPromptToken, BigDecimal perOutputToken) {
        public BigDecimal upperBound(Model.Request r) {
            return perOutputToken.multiply(BigDecimal.valueOf(r.maxTokens()))
                    .add(perPromptToken.multiply(BigDecimal.valueOf(estimatePromptTokens(r.prompt()))));
        }
        public BigDecimal actual(Model.Response resp) {
            return perPromptToken.multiply(BigDecimal.valueOf(resp.promptTokens()))
                    .add(perOutputToken.multiply(BigDecimal.valueOf(resp.outputTokens())));
        }
    }

    /**
     * Deliberately crude, and deliberately an OVER-estimate.
     *
     * This number only sets the hold. A hold that is too large is refunded
     * moments later and costs nothing; a hold that is too small means the real
     * cost exceeds the authorisation, which is recoverable but is an overage to
     * explain. English is roughly four characters per token, so dividing by two
     * leaves a wide margin on purpose. Erring generous is free, so err generous.
     */
    static int estimatePromptTokens(String prompt) {
        return Math.max(8, prompt.length() / 2);
    }

    private final Mode mode;
    private final Model model;
    private final Pricing pricing;
    private final UUID runId;

    public Decider(Mode mode, Model model, Pricing pricing, UUID runId) {
        this.mode = mode;
        this.model = model;
        this.pricing = pricing;
        this.runId = runId;
    }

    /**
     * Decide, with the seeded answer standing by.
     *
     * seededFallback is not an error path bolted on afterwards, it is the
     * floor: a visitor always has a decision, whatever the model does. The
     * model gets to make it better, never to make it impossible.
     */
    public Outcome decide(Model.Request request, String seededFallback, Instant at) throws Exception {
        if (mode == Mode.SEEDED) {
            return new Outcome(seededFallback, Source.SEEDED, null, BigDecimal.ZERO, 0);
        }

        String fingerprint = Cassettes.fingerprint(model.id(), model.version(), request);
        Cassettes.Entry hit = Cassettes.find(fingerprint, request.prompt());
        if (hit != null) {
            // free, byte-identical, and the model is not touched
            return new Outcome(hit.response(), Source.CASSETTE, fingerprint, BigDecimal.ZERO, 0);
        }

        if (mode == Mode.REPLAY) {
            // THE RULE THIS SERVICE MOST DEPENDS ON. A miss must never quietly
            // become a live call: that would turn a reproducible run into a
            // live one with no record that it happened, and spend money nobody
            // asked to spend. Test infrastructure fails by silently degrading
            // into non-determinism, and this is where that would begin.
            throw new Cassettes.Miss(fingerprint);
        }

        // LIVE. Reserve the ceiling BEFORE the call, so a run that cannot
        // afford the worst case never makes the call at all.
        UUID hold = Budget.authorize(runId, pricing.upperBound(request), at);

        // ONLY THE CALL ITSELF IS IN THE FALLBACK PATH.
        //
        // The first version wrapped the call, the capture and the recording in
        // one try, so anything thrown while BILLING was treated as the model
        // having failed: the hold was voided, the visitor fell back, and fifty
        // model calls that genuinely happened were recorded as free. A failure
        // to bill is not a failure to call, and conflating them loses money in
        // the direction nobody notices.
        Model.Response response;
        try {
            response = model.complete(request);
        } catch (Exception e) {
            // nothing came back, so nothing may be charged. A void that does not
            // restore the hold exactly is a slow leak that looks like nothing
            // until a run dies short of its ticks.
            Budget.voidHold(hold, at);
            return new Outcome(seededFallback, Source.SEEDED, fingerprint, BigDecimal.ZERO, 0);
        }

        // the call happened and the vendor will bill for it, so this is settled
        // whatever else goes wrong afterwards
        Budget.capture(hold, pricing.actual(response), at);
        Cassettes.record(fingerprint, model.id(), model.version(), request.templateId(),
                request.prompt(), response.text(), response.promptTokens(), response.outputTokens());
        return new Outcome(response.text(), Source.MODEL, fingerprint,
                pricing.actual(response), response.latencyMs());
    }

    public Mode mode() { return mode; }
}
