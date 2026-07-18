package dev.visitors.model;

/**
 * The one thing the rest of the system knows about a language model.
 *
 * Deliberately tiny, and deliberately not a framework. What matters here is not
 * the model, it is the shell around it: a non-deterministic, priced,
 * rate-limited, occasionally-wrong external dependency. Swap this interface's
 * implementation for a sanctions-screening vendor or an FX-rate provider and
 * almost everything else in this service still applies, which is the test of
 * whether the shell was built for the right reason.
 */
public interface Model {

    /** What was asked, in the form that is hashed to key a cassette. */
    record Request(String templateId, String prompt, int maxTokens, double temperature) {}

    /** What came back, plus what it cost. */
    record Response(String text, int promptTokens, int outputTokens, int latencyMs) {}

    String id();

    /**
     * The version is pinned and recorded, never inferred.
     *
     * "temperature=0 gives determinism" is false: batched inference and
     * non-deterministic kernels break it, and a vendor can change the weights
     * behind a stable name without telling anybody. So a version is part of the
     * cassette key, and a version change registers as a conflict rather than as
     * a run that quietly means something different from the one before it.
     */
    String version();

    Response complete(Request request) throws Exception;
}
