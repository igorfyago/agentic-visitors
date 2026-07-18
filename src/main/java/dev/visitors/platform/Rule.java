package dev.visitors.platform;

import java.util.ArrayList;
import java.util.List;

/**
 * A CLASSIFIER EXPRESSION, WRITTEN BY THE MANIFEST.
 *
 * "What counts as success" is a platform fact, not a core one, and it varies
 * more than anybody expects. minimart answers 200 for a successful subscribe.
 * A different platform answers 201 for a created resource, and another answers
 * 200 with an error key in the body. A core that hardcodes any of those has
 * learned one platform's conventions and calls them universal.
 *
 * So the rule lives in the manifest as a small expression:
 *
 *   status:200 &amp; !body.has:error
 *   status:201 | status:200
 *   status:4xx | (status:200 &amp; body.has:error)
 *
 * Grammar, deliberately tiny: terms joined by &amp; and |, negated with !, grouped
 * with parentheses. & binds tighter than |. No user-defined names, no
 * arithmetic, nothing that could turn into a language. It exists to express
 * response classification and to be unable to express anything else.
 */
public sealed interface Rule {

    boolean test(int status, String body);

    record Status(String pattern) implements Rule {
        public boolean test(int status, String body) {
            if (pattern.length() == 3 && pattern.charAt(1) == 'x' && pattern.charAt(2) == 'x') {
                return String.valueOf(status).charAt(0) == pattern.charAt(0);
            }
            return String.valueOf(status).equals(pattern);
        }
    }

    record BodyHas(String key) implements Rule {
        public boolean test(int status, String body) {
            return body != null && body.contains("\"" + key + "\"");
        }
    }

    record BodyEq(String key, String value) implements Rule {
        public boolean test(int status, String body) {
            return value.equals(dev.visitors.core.Json.str(body, key));
        }
    }

    /** For a precheck: the array in the body holds fewer than n of this key. */
    record CountLt(String key, int n) implements Rule {
        public boolean test(int status, String body) {
            return dev.visitors.core.Json.each(body, key).size() < n;
        }
    }

    record Not(Rule inner) implements Rule {
        public boolean test(int status, String body) { return !inner.test(status, body); }
    }

    record And(List<Rule> parts) implements Rule {
        public boolean test(int status, String body) {
            for (Rule r : parts) if (!r.test(status, body)) return false;
            return true;
        }
    }

    record Or(List<Rule> parts) implements Rule {
        public boolean test(int status, String body) {
            for (Rule r : parts) if (r.test(status, body)) return true;
            return false;
        }
    }

    record Never() implements Rule {
        public boolean test(int status, String body) { return false; }
    }

    static Rule parse(String text) {
        if (text == null || text.isBlank()) return new Never();
        Parser p = new Parser(tokenize(text));
        Rule r = p.or();
        if (p.i < p.tokens.size()) throw new IllegalArgumentException(
                "trailing input in rule at '" + p.tokens.get(p.i) + "': " + text);
        return r;
    }

    private static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '(' || c == ')' || c == '&' || c == '|' || c == '!') {
                if (!cur.isEmpty()) { out.add(cur.toString().trim()); cur.setLength(0); }
                out.add(String.valueOf(c));
            } else if (Character.isWhitespace(c)) {
                if (!cur.isEmpty()) { out.add(cur.toString().trim()); cur.setLength(0); }
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) out.add(cur.toString().trim());
        out.removeIf(String::isEmpty);
        return out;
    }

    final class Parser {
        final List<String> tokens;
        int i;

        Parser(List<String> tokens) { this.tokens = tokens; }

        Rule or() {
            List<Rule> parts = new ArrayList<>();
            parts.add(and());
            while (peek("|")) { i++; parts.add(and()); }
            return parts.size() == 1 ? parts.get(0) : new Or(parts);
        }

        Rule and() {
            List<Rule> parts = new ArrayList<>();
            parts.add(unary());
            while (peek("&")) { i++; parts.add(unary()); }
            return parts.size() == 1 ? parts.get(0) : new And(parts);
        }

        Rule unary() {
            if (peek("!")) { i++; return new Not(unary()); }
            if (peek("(")) {
                i++;
                Rule r = or();
                if (!peek(")")) throw new IllegalArgumentException("unclosed ( in rule");
                i++;
                return r;
            }
            return term();
        }

        Rule term() {
            if (i >= tokens.size()) throw new IllegalArgumentException("rule ended early");
            String t = tokens.get(i++);
            int colon = t.indexOf(':');
            if (colon < 0) throw new IllegalArgumentException("not a rule term: " + t);
            String op = t.substring(0, colon), arg = t.substring(colon + 1);
            return switch (op) {
                case "status" -> new Status(arg);
                case "body.has" -> new BodyHas(arg);
                case "body.eq" -> {
                    int eq = arg.indexOf('=');
                    if (eq < 0) throw new IllegalArgumentException("body.eq needs key=value: " + arg);
                    yield new BodyEq(arg.substring(0, eq), arg.substring(eq + 1));
                }
                case "array.count.lt" -> {
                    int eq = arg.indexOf('=');
                    if (eq < 0) throw new IllegalArgumentException("array.count.lt needs key=n: " + arg);
                    yield new CountLt(arg.substring(0, eq), Integer.parseInt(arg.substring(eq + 1)));
                }
                default -> throw new IllegalArgumentException("unknown rule operator: " + op);
            };
        }

        boolean peek(String s) { return i < tokens.size() && tokens.get(i).equals(s); }
    }
}
