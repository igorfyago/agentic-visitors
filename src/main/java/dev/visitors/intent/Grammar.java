package dev.visitors.intent;

import dev.visitors.platform.Manifest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * THE MODEL DOES NOT EMIT ACTIONS. IT EMITS A PROPOSAL.
 *
 * Everything a model produces is untrusted input, and the mistake that makes an
 * agent dangerous is treating a plausible sentence as an instruction. So the
 * output is parsed into a CLOSED ALPHABET declared by the manifest, every
 * parameter is checked against a declared domain, and only then does anything
 * exist that can be executed. An unvalidated intent has no representation in
 * this type system: Intent is constructible only from here.
 *
 * The rejection reasons are a taxonomy rather than one INVALID bucket, because
 * they call for opposite fixes. UNPARSEABLE and UNKNOWN_INTENT say the model
 * does not understand the format, which is a prompt problem. PARAM_UNKNOWN_REF
 * says it understood perfectly and invented an id, which is a grounding
 * problem. Collapsing them into one number would make a prompt fix and a
 * grounding fix indistinguishable in the results.
 *
 * PARAM_UNKNOWN_REF is the interesting one. A visitor may only name ids it has
 * actually SEEN in a response, so a model that hallucinates a product cannot
 * reach the platform with it. This is where "the visitor sees HTTP responses
 * and nothing else" stops being a principle and becomes a gate.
 */
public final class Grammar {

    private Grammar() {}

    /**
     * @param memo answers "have I seen this id, in this named set". The core
     *             never learns what any set contains or means.
     */
    public static Parsed parse(String modelText, Manifest m, BiPredicate<String, String> memo, long identity) {
        if (modelText == null) return Parsed.rejected(Reject.UNPARSEABLE);

        // Take the first line whose first token is in the alphabet. Prose before
        // or after is IGNORED on purpose: a model that explains itself and then
        // acts is behaving normally, and rejecting that would inflate the
        // invalid rate with something that is not a defect.
        String[] lines = modelText.split("\\R");
        String chosen = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String head = trimmed.split("\\s+")[0];
            if (m.alphabet().contains(head)) { chosen = trimmed; break; }
        }
        if (chosen == null) {
            // Did it name something that merely LOOKS like an intent? That is a
            // different failure from producing no recognisable line at all.
            for (String line : lines) {
                String t = line.trim();
                if (!t.isEmpty() && t.split("\\s+")[0].matches("[A-Z_]{3,}")) {
                    return Parsed.rejected(Reject.UNKNOWN_INTENT);
                }
            }
            return Parsed.rejected(Reject.UNPARSEABLE);
        }

        String[] tokens = chosen.split("\\s+");
        String name = tokens[0];
        Manifest.Capability cap = m.cap(name);
        if (cap == null) return Parsed.rejected(Reject.UNKNOWN_INTENT);

        Map<String, String> supplied = new LinkedHashMap<>();
        for (int i = 1; i < tokens.length; i++) {
            int eq = tokens[i].indexOf('=');
            if (eq <= 0) continue;   // stray prose on the action line, not fatal
            supplied.put(tokens[i].substring(0, eq), tokens[i].substring(eq + 1));
        }

        // every supplied param must be one the capability declares AND one the
        // model is allowed to set. A model supplying business_at or its own
        // customer id is trying to forge something the runtime owns.
        for (String key : supplied.keySet()) {
            Manifest.ParamSpec spec = cap.params().get(key);
            if (spec == null || spec.source() != Manifest.Source.MODEL) {
                return Parsed.rejected(Reject.PARAM_EXTRA);
            }
        }

        LinkedHashMap<String, String> bound = new LinkedHashMap<>();
        for (Map.Entry<String, Manifest.ParamSpec> e : cap.params().entrySet()) {
            Manifest.ParamSpec spec = e.getValue();
            switch (spec.source()) {
                case CONST -> bound.put(e.getKey(), spec.constValue());
                case RUNTIME_IDENTITY -> bound.put(e.getKey(), String.valueOf(identity));
                case RUNTIME_CLOCK -> { /* the executor stamps this, never the model */ }
                case MODEL -> {
                    String value = supplied.get(e.getKey());
                    if (value == null) return Parsed.rejected(Reject.MISSING_PARAM);
                    Reject bad = check(spec.domain(), value, memo);
                    if (bad != null) return Parsed.rejected(bad);
                    bound.put(e.getKey(), value);
                }
            }
        }
        return Parsed.accepted(new Intent(name, bound));
    }

    private static Reject check(Manifest.Domain domain, String value, BiPredicate<String, String> memo) {
        return switch (domain) {
            case Manifest.Domain.InSet s ->
                    memo.test(s.setName(), value) ? null : Reject.PARAM_UNKNOWN_REF;
            case Manifest.Domain.IntRange r -> {
                long n;
                try { n = Long.parseLong(value); }
                catch (NumberFormatException e) { yield Reject.PARAM_TYPE; }
                // the right shape but out of bounds is a DIFFERENT failure from
                // the wrong shape: one is a model that cannot count, the other
                // is a model that cannot format
                yield (n < r.lo() || n > r.hi()) ? Reject.PARAM_RANGE : null;
            }
            case Manifest.Domain.OneOf o -> o.values().contains(value) ? null : Reject.PARAM_RANGE;
            case Manifest.Domain.Text t -> value.length() > t.max() ? Reject.PARAM_RANGE : null;
        };
    }
}
