package dev.visitors.run;

import dev.visitors.core.Json;
import dev.visitors.intent.Intent;
import dev.visitors.platform.Classifier;
import dev.visitors.platform.Manifest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * TURN A VALIDATED INTENT INTO ONE HTTP CALL, AND RECORD WHAT HAPPENED.
 *
 * Nothing in this file names an endpoint, a field, a status code or a product.
 * Every one of those comes from the manifest, which is what makes "adding a
 * platform is a manifest file and zero core changes" a testable claim rather
 * than a slogan. The test suite scans this package for platform vocabulary and
 * fails if any appears.
 *
 * Two things the model is never allowed to supply, enforced here rather than
 * hoped for: the business time and the visitor's identity. A model that could
 * set either could move the clock or act as somebody else, and both would be
 * invisible in the results.
 */
public final class Executor {

    public record Result(String outcome, String reason, int status, String body) {}

    private final Manifest manifest;
    private final HttpClient http;
    private final UUID runId;

    public Executor(Manifest manifest, UUID runId) {
        this.manifest = manifest;
        this.runId = runId;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(manifest.connectTimeoutMs()))
                .build();
    }

    /**
     * Execute, or decline to because this position was already claimed.
     *
     * Returns null when the claim was taken, which is the replay case: the same
     * visitor, tick and step derives the same key, the insert conflicts, and
     * nothing is sent. That is the property inherited from minimart's derived
     * order ids, kept alive across a non-deterministic model.
     */
    public Result run(Manifest.Capability cap, Intent intent, long decisionId,
                      int visitorId, int tick, int step, Instant businessAt) throws SQLException {
        if (cap.kind() == Manifest.Kind.NOOP) return null;

        String key = Keys.idem(runId, visitorId, tick, step);
        OptionalLong claim = Calls.claim(runId, decisionId, manifest.id(), cap.name(), key, businessAt);
        if (claim.isEmpty()) return null;   // already sent for this position
        long callId = claim.getAsLong();

        String path = cap.path();
        for (Map.Entry<String, String> e : intent.params().entrySet()) {
            path = path.replace("{" + e.getKey() + "}", e.getValue());
        }

        String body = buildBody(cap, intent, businessAt);
        int status = 0;
        String responseBody = null;
        Throwable failure = null;
        long started = System.nanoTime();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(manifest.baseUrl() + path))
                    .timeout(Duration.ofMillis(manifest.readTimeoutMs()));
            if ("GET".equals(cap.method())) b.GET();
            else b.method(cap.method(), HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            status = r.statusCode();
            responseBody = r.body();
        } catch (IOException | InterruptedException e) {
            failure = e;
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        int latency = (int) ((System.nanoTime() - started) / 1_000_000);

        Classifier.Verdict verdict = Classifier.of(cap, status, responseBody, failure);
        Calls.settle(callId, failure == null ? status : null,
                verdict.outcome(), verdict.reason(), responseBody, latency);
        return new Result(verdict.outcome(), verdict.reason(), status, responseBody);
    }

    /** The request body, built from the manifest's declared field list. */
    private String buildBody(Manifest.Capability cap, Intent intent, Instant businessAt) {
        if (cap.bodyFields().isEmpty()) return "";
        StringBuilder b = new StringBuilder("{");
        boolean first = true;
        for (String field : cap.bodyFields()) {
            Manifest.ParamSpec spec = cap.params().get(field);
            String value = spec.source() == Manifest.Source.RUNTIME_CLOCK
                    // THE CLOCK IS OURS. A model that could set this could move
                    // business time, and a run whose time moved for reasons
                    // nobody recorded is not a run, it is an anecdote.
                    ? businessAt.toString()
                    : intent.params().get(field);
            if (value == null) continue;
            if (!first) b.append(',');
            first = false;
            b.append('"').append(Json.esc(field)).append("\":\"").append(Json.esc(value)).append('"');
        }
        return b.append('}').toString();
    }
}
