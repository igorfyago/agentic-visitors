package dev.visitors.run;

import dev.visitors.core.Json;
import dev.visitors.platform.Manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EVERYTHING THIS VISITOR HAS SEEN, AND NOTHING ELSE.
 *
 * The doctrine says a visitor sees HTTP responses and nothing else, and that a
 * visitor knowing something only the database knows is cheating. This class is
 * that rule expressed as a type rather than left as an instruction: the ONLY
 * way an id gets in here is by having appeared in a response body the platform
 * actually returned.
 *
 * Which matters because the grammar refuses any id the memo has not seen. A
 * model that invents a plausible product cannot reach the platform with it, and
 * a harness that quietly seeded this from a config file would defeat the whole
 * exercise while every test still passed.
 *
 * The set NAMES come from the manifest. The core never learns what a set holds
 * or means, only that a capability captures into one and a param is validated
 * against one.
 */
public final class Memo {

    private final Map<String, List<String>> sets = new LinkedHashMap<>();

    /** Absorb what a LANDED response revealed. Called with nothing else. */
    public void absorb(Manifest.Capability c, String body) {
        for (Map.Entry<String, String> e : c.captureEach().entrySet()) {
            List<String> target = sets.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
            for (String v : Json.each(body, e.getValue())) {
                if (!target.contains(v)) target.add(v);
            }
        }
    }

    public boolean has(String setName, String id) {
        List<String> s = sets.get(setName);
        return s != null && s.contains(id);
    }

    public List<String> set(String setName) {
        return sets.getOrDefault(setName, List.of());
    }

    /**
     * The memo as the model sees it.
     *
     * Rendered from a LinkedHashMap over insertion-ordered lists, so the same
     * observations always produce the same bytes. That is not tidiness: these
     * bytes go into the prompt, the prompt is hashed into the cassette key, and
     * any wobble in the ordering silently destroys the hit rate and silently
     * escalates spend.
     */
    public String render() {
        if (sets.isEmpty()) return "You have not seen anything yet.\n";
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, List<String>> e : sets.entrySet()) {
            b.append(e.getKey()).append(" you have seen: ")
             .append(String.join(", ", e.getValue())).append('\n');
        }
        return b.toString();
    }
}
