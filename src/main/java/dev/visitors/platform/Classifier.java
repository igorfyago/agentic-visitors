package dev.visitors.platform;

/**
 * WHAT HAPPENED, IN FOUR WORDS, AND WHY.
 *
 * The distinction that carries the weight is between REFUSED and ERROR.
 *
 *   LANDED  · it happened.
 *   REFUSED · it did not happen, and the platform decided that. A perfectly
 *             valid request the world said no to. This is information about the
 *             WORLD, and a run full of refusals is a legitimate run.
 *   ERROR   · it did not happen, and something broke. Information about US.
 *   PENDING · WE DO NOT KNOW.
 *
 * PENDING is the one that is usually missing, and its absence is a lie. A read
 * timeout means the request may well have been received, executed and committed
 * while the response was lost on the way back. Recording that as ERROR asserts
 * knowledge nobody has, and every number computed from it inherits the false
 * confidence. So a timeout is PENDING, and a PENDING row is a question left
 * open rather than an answer invented.
 */
public final class Classifier {

    public record Verdict(String outcome, String reason) {}

    private Classifier() {}

    public static Verdict of(Manifest.Capability c, int status, String body, Throwable failure) {
        if (failure != null) {
            // A CONNECT failure means the request never left. A READ failure
            // means it left and we lost the answer, which is a different fact
            // and must not be flattened into the same one.
            boolean neverSent = failure instanceof java.net.ConnectException
                    || failure instanceof java.nio.channels.UnresolvedAddressException;
            return neverSent
                    ? new Verdict("ERROR", "connect: " + failure.getClass().getSimpleName())
                    : new Verdict("PENDING", "no response: " + failure.getClass().getSimpleName()
                            + ". The request may have been applied.");
        }
        if (c.landed().test(status, body)) return new Verdict("LANDED", "matched landed rule");
        if (c.refused().test(status, body)) return new Verdict("REFUSED", "matched refused rule, status " + status);
        return new Verdict("ERROR", "status " + status + " matched neither landed nor refused");
    }
}
