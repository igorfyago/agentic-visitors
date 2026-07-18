package dev.visitors.platform;

import dev.visitors.core.Json;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EVERYTHING THIS SYSTEM KNOWS ABOUT A PLATFORM, IN A FILE.
 *
 * The claim this class exists to make true is one sentence: adding a platform
 * is a manifest file and zero changes to the core. That claim is easy to make
 * and easy to fake, and the usual way it is faked is subtle. The manifest names
 * the endpoints while the core still knows what a catalogue is, what a
 * subscription is, that success means 200, and that the tenant is called helix.
 * Then the second platform arrives and every one of those is a core edit.
 *
 * So the rules here are strict, and the test suite enforces them by scanning
 * the compiled sources for platform vocabulary:
 *
 *   the intent alphabet is a manifest VALUE, not a Java enum,
 *   param domains are named SETS whose names the manifest chooses,
 *   constants like the tenant and the location are manifest values,
 *   success and refusal are manifest expressions,
 *   and the identity offset is a manifest number.
 *
 * FORMAT. Flat JSON, dotted keys, every value a string. That looks primitive
 * next to a nested document, and it is deliberate: the codec in this repo is a
 * scanner with no nesting support, and reaching for a JSON library to read a
 * config file would be the first dependency in a repo whose whole argument is
 * that it has none. A dotted key is an exact quoted needle, so lookup is
 * unambiguous and costs nothing.
 */
public final class Manifest {

    public enum Kind { CALL, NOOP }

    /** Where a parameter's value comes from. The model supplies some, the
     *  manifest fixes others, and the runtime owns the two it must not let
     *  either of them forge: the clock and the visitor's identity. */
    public enum Source { MODEL, CONST, RUNTIME_CLOCK, RUNTIME_IDENTITY }

    /** What a model-supplied value is allowed to be. A domain the manifest
     *  names, never a type the core enumerates. */
    public sealed interface Domain {
        /** One of the ids the visitor has actually seen in a response body. */
        record InSet(String setName) implements Domain {}
        record IntRange(long lo, long hi) implements Domain {}
        record OneOf(List<String> values) implements Domain {}
        record Text(int max) implements Domain {}
    }

    public record ParamSpec(String name, Source source, String constValue, Domain domain) {}

    public record Capability(String name, Kind kind, String method, String path,
                             List<String> bodyFields, Map<String, ParamSpec> params,
                             Rule landed, Rule refused,
                             Map<String, String> captureEach, String description) {}

    public record Precheck(String path, Rule require, String note) {}

    private final Map<String, String> keys;
    private final String id, baseUrl, raw;
    private final List<String> alphabet;
    private final Map<String, Capability> caps = new LinkedHashMap<>();
    private final List<Precheck> prechecks = new ArrayList<>();
    private final Capability clock;
    private final String fallbackIntent;
    private final long identityBase;
    private final int connectTimeoutMs, readTimeoutMs;

    private Manifest(String raw) {
        this.raw = raw;
        this.keys = Map.of();
        this.id = require(raw, "platform.id");
        this.baseUrl = require(raw, "platform.base_url");
        this.identityBase = Long.parseLong(require(raw, "platform.identity_base"));
        this.connectTimeoutMs = Integer.parseInt(require(raw, "platform.connect_timeout_ms"));
        this.readTimeoutMs = Integer.parseInt(require(raw, "platform.read_timeout_ms"));
        this.alphabet = List.of(require(raw, "alphabet").split(","));
        this.fallbackIntent = require(raw, "fallback.intent");

        for (String intent : alphabet) {
            caps.put(intent, capability(raw, intent));
        }
        if (!caps.containsKey(fallbackIntent)) {
            throw new IllegalArgumentException("fallback.intent " + fallbackIntent + " is not in the alphabet");
        }
        this.clock = capability(raw, "clock");

        for (int n = 1; ; n++) {
            String path = Json.str(raw, "precheck." + n + ".path");
            if (path == null) break;
            prechecks.add(new Precheck(path,
                    Rule.parse(require(raw, "precheck." + n + ".require")),
                    Json.str(raw, "precheck." + n + ".note")));
        }
    }

    public static Manifest load(String resourcePath) {
        try (InputStream in = Manifest.class.getResourceAsStream(resourcePath)) {
            if (in == null) throw new IllegalArgumentException("no manifest at " + resourcePath);
            return new Manifest(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not read manifest " + resourcePath, e);
        }
    }

    public static Manifest of(String text) { return new Manifest(text); }

    private static Capability capability(String raw, String name) {
        String kindText = require(raw, name + ".kind");
        Kind kind = Kind.valueOf(kindText.toUpperCase());
        if (kind == Kind.NOOP) {
            return new Capability(name, kind, null, null, List.of(), Map.of(),
                    new Rule.Never(), new Rule.Never(), Map.of(),
                    Json.str(raw, name + ".description"));
        }

        String method = require(raw, name + ".method");
        String path = require(raw, name + ".path");
        String bodyText = Json.str(raw, name + ".body");
        List<String> body = bodyText == null || bodyText.isBlank()
                ? List.of() : List.of(bodyText.split(","));

        Map<String, ParamSpec> params = new LinkedHashMap<>();
        String paramList = Json.str(raw, name + ".params");
        List<String> declared = paramList == null || paramList.isBlank()
                ? List.of() : List.of(paramList.split(","));
        for (String p : declared) params.put(p, paramSpec(raw, name, p));
        // a body field must be a declared param, or the request could never be built
        for (String f : body) {
            if (!params.containsKey(f)) {
                throw new IllegalArgumentException(name + ".body names " + f + " which is not in " + name + ".params");
            }
        }

        Map<String, String> captureEach = new LinkedHashMap<>();
        for (String key : List.of("capture_each")) {
            String prefix = "\"" + name + "." + key + ".";
            int from = 0;
            while (true) {
                int i = raw.indexOf(prefix, from);
                if (i < 0) break;
                int end = raw.indexOf('"', i + prefix.length());
                String setName = raw.substring(i + prefix.length(), end);
                captureEach.put(setName, require(raw, name + "." + key + "." + setName));
                from = end;
            }
        }

        return new Capability(name, kind, method, path, body, params,
                Rule.parse(require(raw, name + ".landed")),
                Rule.parse(Json.str(raw, name + ".refused") == null ? "" : Json.str(raw, name + ".refused")),
                captureEach, Json.str(raw, name + ".description"));
    }

    private static ParamSpec paramSpec(String raw, String cap, String param) {
        String base = cap + ".param." + param;
        String sourceText = require(raw, base + ".source");
        if (sourceText.startsWith("const:")) {
            return new ParamSpec(param, Source.CONST, sourceText.substring(6), new Domain.Text(256));
        }
        if (sourceText.equals("runtime:clock")) {
            return new ParamSpec(param, Source.RUNTIME_CLOCK, null, new Domain.Text(64));
        }
        if (sourceText.equals("runtime:identity")) {
            return new ParamSpec(param, Source.RUNTIME_IDENTITY, null, new Domain.Text(64));
        }
        if (!sourceText.equals("model")) {
            throw new IllegalArgumentException(base + ".source is not model, const: or runtime:: " + sourceText);
        }
        String domainText = require(raw, base + ".domain");
        Domain domain;
        if (domainText.startsWith("set:")) {
            domain = new Domain.InSet(domainText.substring(4));
        } else if (domainText.startsWith("int:")) {
            String[] bounds = domainText.substring(4).split("\\.\\.");
            domain = new Domain.IntRange(Long.parseLong(bounds[0]), Long.parseLong(bounds[1]));
        } else if (domainText.startsWith("oneof:")) {
            domain = new Domain.OneOf(List.of(domainText.substring(6).split("\\|")));
        } else if (domainText.startsWith("text:")) {
            domain = new Domain.Text(Integer.parseInt(domainText.substring(5)));
        } else {
            throw new IllegalArgumentException(base + ".domain is not set:, int:, oneof: or text:: " + domainText);
        }
        return new ParamSpec(param, Source.MODEL, null, domain);
    }

    private static String require(String raw, String key) {
        String v = Json.str(raw, key);
        if (v == null) throw new IllegalArgumentException("manifest is missing " + key);
        return v;
    }

    public String id() { return id; }
    public String baseUrl() { return baseUrl; }
    public List<String> alphabet() { return alphabet; }
    public Capability cap(String intent) { return caps.get(intent); }
    public Capability clock() { return clock; }
    public String fallbackIntent() { return fallbackIntent; }
    public long identityBase() { return identityBase; }
    public int connectTimeoutMs() { return connectTimeoutMs; }
    public int readTimeoutMs() { return readTimeoutMs; }
    public List<Precheck> prechecks() { return prechecks; }

    /**
     * The tool list handed to the model, rendered from the manifest alone.
     *
     * Determinism matters more here than anywhere else in the repo: this text
     * goes into the prompt, the prompt bytes are hashed into the cassette key,
     * and any wobble in the ordering silently destroys the hit rate and
     * silently escalates spend. Everything iterated below is a List or a
     * LinkedHashMap, never a HashMap or a Set.
     */
    public String toolLines() {
        StringBuilder b = new StringBuilder();
        for (String intent : alphabet) {
            Capability c = caps.get(intent);
            b.append(intent);
            for (Map.Entry<String, ParamSpec> e : c.params().entrySet()) {
                if (e.getValue().source() == Source.MODEL) b.append(' ').append(e.getKey()).append("=<").append(describe(e.getValue().domain())).append('>');
            }
            if (c.description() != null) b.append("   ").append(c.description());
            b.append('\n');
        }
        return b.toString();
    }

    private static String describe(Domain d) {
        return switch (d) {
            case Domain.InSet s -> "one of the " + s.setName() + " you have seen";
            case Domain.IntRange r -> r.lo() + " to " + r.hi();
            case Domain.OneOf o -> String.join("|", o.values());
            case Domain.Text t -> "text";
        };
    }

    /** A short digest of the whole file, so a run records exactly which version
     *  of the platform description it was driven by. A manifest edit is a new
     *  experiment, and this is what makes that detectable rather than a matter
     *  of somebody's memory. */
    public String sha12() {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                b.append(Character.forDigit((d[i] >> 4) & 0xF, 16)).append(Character.forDigit(d[i] & 0xF, 16));
            }
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
