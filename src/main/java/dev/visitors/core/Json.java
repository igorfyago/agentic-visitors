package dev.visitors.core;

/** A tiny JSON codec. Deliberately duplicated per service: a shared model
 *  object between a merchant and its processor would be a coupling neither
 *  should have. */
public final class Json {

    private Json() {}

    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch)); else b.append(ch); }
            }
        }
        return b.toString();
    }

    /** Build a flat object from alternating key, value pairs. Every value is
     *  written as a string, including the numbers: an event payload is a
     *  CONTRACT, and a reader that must guess whether "amount" arrives as
     *  79 or 79.00 or "79.00" is a reader that will eventually guess wrong. */
    public static String obj(String... kv) {
        if (kv.length % 2 != 0) throw new IllegalArgumentException("obj() needs pairs");
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) b.append(',');
            b.append('"').append(esc(kv[i])).append("\":\"").append(esc(kv[i + 1])).append('"');
        }
        return b.append('}').toString();
    }

    /**
     * Every value for a key, in document order.
     *
     * str() returns only the FIRST match in the whole document, which is fine
     * for a flat object and useless for an array of them. A catalogue endpoint
     * answering [{"id":"a"},{"id":"b"}] would yield only "a", and a visitor
     * whose known-id set silently holds one entry rejects every legitimate
     * choice but one, with nothing to indicate why.
     *
     * Still a scanner, not a parser. It cannot tell a nested object's "id" from
     * a top-level one, and for the flat arrays these APIs return that is
     * sufficient. Stated here so the limit is a known cost rather than a
     * surprise later.
     */
    public static java.util.List<String> each(String json, String key) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (json == null) return out;
        String needle = "\"" + key + "\"";
        int from = 0;
        while (true) {
            int i = json.indexOf(needle, from);
            if (i < 0) return out;
            String v = str(json.substring(i), key);
            if (v != null) out.add(v);
            from = i + needle.length();
        }
    }

    public static String str(String json, String key) {
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) return null;
        i = json.indexOf(':', i + needle.length());
        if (i < 0) return null;
        i++;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length()) return null;
        if (json.charAt(i) == '"') {
            StringBuilder b = new StringBuilder();
            for (int j = i + 1; j < json.length(); j++) {
                char ch = json.charAt(j);
                if (ch == '\\' && j + 1 < json.length()) { b.append(json.charAt(++j)); continue; }
                if (ch == '"') break;
                b.append(ch);
            }
            return b.toString();
        }
        // Unquoted scalars: numbers, and also true, false and null.
        //
        // The first version accepted only numeric characters, so every JSON
        // BOOLEAN read back as null. That is not a cosmetic gap: the manifest's
        // own precheck rule is body.eq:running=false against an endpoint that
        // answers "running":false, so the rule could never match and the check
        // it guards would have failed every time, for a reason nothing would
        // have explained.
        int end = i;
        while (end < json.length() && "-+.0123456789eE".indexOf(json.charAt(end)) >= 0) end++;
        if (end > i) return json.substring(i, end);
        for (String literal : new String[]{"true", "false", "null"}) {
            if (json.startsWith(literal, i)) return literal;
        }
        return null;
    }
}
