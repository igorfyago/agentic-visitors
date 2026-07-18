package dev.visitors.intent;

import dev.visitors.core.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A proposal that has passed every gate.
 *
 * Package-private constructor on purpose: the only way to obtain one is through
 * Grammar, so an unvalidated intent has no representation in the type system.
 * A method that takes an Intent cannot be handed raw model output by mistake.
 *
 * LinkedHashMap, not HashMap. The order of these params reaches the request
 * body and therefore the recorded call, and iteration order that varies between
 * runs would make two identical decisions produce different bytes.
 */
public record Intent(String name, LinkedHashMap<String, String> params) {

    public Intent {
        params = new LinkedHashMap<>(params);
    }

    public String paramsJson() {
        StringBuilder b = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) b.append(',');
            first = false;
            b.append('"').append(Json.esc(e.getKey())).append("\":\"").append(Json.esc(e.getValue())).append('"');
        }
        return b.append('}').toString();
    }
}
