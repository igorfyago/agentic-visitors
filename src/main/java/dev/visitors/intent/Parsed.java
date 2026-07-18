package dev.visitors.intent;

/**
 * An Intent or a Reject, never both and never neither.
 *
 * Returning a nullable Intent would make "the model was rejected" and "we
 * forgot to check" the same value at the call site, which is the shape of bug
 * that ends with unvalidated input being executed because one branch forgot a
 * null test.
 */
public record Parsed(Intent intent, Reject reject) {

    public static Parsed accepted(Intent i) { return new Parsed(i, null); }
    public static Parsed rejected(Reject r) { return new Parsed(null, r); }

    public boolean ok() { return intent != null; }
}
