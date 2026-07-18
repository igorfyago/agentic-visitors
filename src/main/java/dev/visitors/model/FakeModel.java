package dev.visitors.model;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A model that costs nothing and does exactly what it is told.
 *
 * The whole first slice of this service is built and proven against this,
 * before a single real token is bought. If the shell is right, the real model
 * is a plug; if the shell is wrong, discovering that with an invoice attached
 * would be an expensive way to learn it.
 *
 * callCount is the point of the class. Several lessons assert that the model
 * was NOT called, and a test that cannot see that has not proved anything about
 * caching or replay.
 */
public final class FakeModel implements Model {

    private final String id;
    private final String version;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile java.util.function.Function<Request, Response> behaviour;

    public FakeModel(String id, String version) {
        this(id, version, r -> new Response("{\"intent\":\"BROWSE\"}", 10, 5, 1));
    }

    public FakeModel(String id, String version, java.util.function.Function<Request, Response> behaviour) {
        this.id = id;
        this.version = version;
        this.behaviour = behaviour;
    }

    @Override public String id() { return id; }
    @Override public String version() { return version; }

    @Override
    public Response complete(Request request) {
        calls.incrementAndGet();
        return behaviour.apply(request);
    }

    public int callCount() { return calls.get(); }
    public void reset() { calls.set(0); }

    public void behave(java.util.function.Function<Request, Response> f) { this.behaviour = f; }

    /** A model that is simply down. Not an exotic case: it is the common one. */
    public static FakeModel failing(String id, String version, String message) {
        return new FakeModel(id, version, r -> { throw new RuntimeException(message); });
    }
}
