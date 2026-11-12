package dev.iconn.services;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The shapes of iconn's services, each a regl schema: one for the real service (its
 * connection details) and one for the mocked service (its {@code mock}), per type.
 *
 * <pre>
 * fraud.check:            # real: http.regl.yaml
 *   type: http
 *   url: https://fraud-api.example/v1
 *
 * fraud.check:            # mocked: http.mock.regl.yaml
 *   type: http
 *   mock:
 *     - when: POST /score
 *       body: {riskScore: 0.05}
 * </pre>
 *
 * regl has no "either this or that"; this class is that choice: {@code type} names the
 * service, {@code mock} makes it mocked. Any regl engine then checks the service against the
 * schema ({@link #check}), and {@link #words} says what the engine found in plain words.
 * The class has no dependencies, so kfig and iconn's loader check a service the same way.
 */
public final class ServiceShapes {

    /** A type of service: its name in files, what people call it, how a message names one. */
    public record Type(String id, String label, String noun) {}

    /** The types, in the order a form offers them. */
    public static final List<Type> TYPES = List.of(
            new Type("http", "HTTP endpoint", "An HTTP endpoint"),
            new Type("queue", "Queue", "A queue"),
            new Type("database", "Database", "A database"),
            new Type("storage", "Storage", "A storage service"),
            new Type("cache", "Cache", "A cache"),
            new Type("email", "Email server", "An email server"));

    /** The fields of one answer of a mocked HTTP endpoint. */
    public static final List<String> ANSWER_FIELDS = List.of("when", "status", "body", "headers", "delay");

    /** What a mock of each other type may hold. */
    private static final Map<String, String> MOCK_FIELDS = Map.of(
            "queue", "messages", "database", "schema, data", "storage", "files", "cache", "entries", "email", "");

    /** The schema a service is checked with: {@code http.regl.yaml}, {@code http.mock.regl.yaml}… */
    public record Shape(Type type, boolean mocked, String name, String schema) {}

    /** One finding of a regl engine: its kind ({@code RULE_FAILED}, {@code UNKNOWN_FIELD}…), path and rule. */
    public record Finding(String kind, String path, String rule) {}

    private static final Map<String, String> SCHEMAS = new LinkedHashMap<>();

    private ServiceShapes() {}

    /** The ids of the types, for messages: {@code http, queue, …}. */
    public static String ids() {
        List<String> ids = new ArrayList<>();
        for (Type t : TYPES) ids.add(t.id());
        return String.join(", ", ids);
    }

    public static Type type(String id) {
        for (Type t : TYPES) if (t.id().equals(id)) return t;
        return null;
    }

    /** Whether {@code service} is mocked: it has {@code mock}. */
    public static boolean isMocked(Map<?, ?> service) {
        return service.containsKey("mock");
    }

    /**
     * The schema {@code service} is checked with, or {@link IllegalArgumentException} with
     * the message to show when it has no type, or one iconn does not know.
     */
    public static Shape shapeOf(Map<?, ?> service) {
        Object raw = service.get("type");
        Type type = raw instanceof String s ? type(s) : null;
        if (type == null) {
            throw new IllegalArgumentException(raw == null
                    ? "A block value is a service: give it a type (" + ids() + ")"
                    : "Unknown service type \"" + raw + "\": use " + ids());
        }
        boolean mocked = isMocked(service);
        String name = type.id() + (mocked ? ".mock" : "") + ".regl.yaml";
        return new Shape(type, mocked, name, schema(name));
    }

    /** The text of one of the schemas, by name ({@code http.mock.regl.yaml}). */
    public static synchronized String schema(String name) {
        return SCHEMAS.computeIfAbsent(name, n -> {
            try (InputStream in = ServiceShapes.class.getResourceAsStream(n)) {
                if (in == null) throw new IllegalArgumentException("No service schema " + n);
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /**
     * What is wrong with {@code service}, in plain words; empty when it is a valid service.
     * {@code regl} validates a document against a schema's text with any regl engine.
     */
    public static List<String> check(Map<?, ?> service, BiFunction<String, Map<?, ?>, List<Finding>> regl) {
        Shape shape;
        try {
            shape = shapeOf(service);
        } catch (IllegalArgumentException e) {
            return List.of(e.getMessage());
        }
        return words(service, shape, regl.apply(shape.schema(), service));
    }

    private static final Pattern ANSWER = Pattern.compile("mock\\.(\\d+)(?:\\.(.+))?");

    /** The findings of a regl engine on {@code service} against its shape, in plain words, each once. */
    public static List<String> words(Map<?, ?> service, Shape shape, List<Finding> findings) {
        Set<String> out = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();
        List<String> extra = new ArrayList<>();
        String noun = shape.type().noun();
        String mockNoun = "the mock of " + Character.toLowerCase(noun.charAt(0)) + noun.substring(1);
        for (Finding f : findings) {
            String path = f.path() == null ? "" : f.path();
            Object value = valueAt(service, path);
            String not = value == null ? "" : ", not " + value;
            boolean unknown = "UNKNOWN_FIELD".equals(f.kind());
            boolean absent = "RULE_FAILED".equals(f.kind()) && ("notNull".equals(f.rule()) || "notEmpty".equals(f.rule()));

            if (!shape.mocked()) {
                if (absent && !path.contains(".")) missing.add(path);
                else if ("url".equals(path)) out.add("url of an HTTP endpoint starts with http:// or https://");
                else if ("port".equals(path)) out.add("port is a number from 1 to 65535" + not);
                else if ("timeoutMs".equals(path) || "poolSize".equals(path)) out.add(path + " is a whole number above 0" + not);
                else if ("ssl".equals(path)) out.add("ssl is true or false" + not);
                else out.add(path + " is not right: " + (f.rule() != null && !"null".equals(f.rule()) ? f.rule() : f.kind()));
                continue;
            }
            if (unknown && !path.contains(".")) {
                extra.add(path);
                continue;
            }
            if ("http".equals(shape.type().id())) {
                Matcher m = ANSWER.matcher(path);
                if (path.startsWith("mock.*") && "UNREACHABLE".equals(f.kind()) && service.get("mock") instanceof List<?> answers) {
                    // regl names the declared path here; the answers that are not blocks are these
                    for (int i = 0; i < answers.size(); i++) {
                        if (!(answers.get(i) instanceof Map<?, ?>)) {
                            out.add("answer " + (i + 1) + " is a block: when: GET /path, then status, body, headers or delay");
                        }
                    }
                    continue;
                }
                if (path.equals("mock") || path.startsWith("mock.*") || !m.matches()) {
                    out.add("the mock of an HTTP endpoint is a list of answers, each with when: METHOD /path");
                    continue;
                }
                String which = "answer " + (Integer.parseInt(m.group(1)) + 1);
                String field = m.group(2);
                if (field == null || "UNREACHABLE".equals(f.kind())) {
                    out.add(which + " is a block: when: GET /path, then status, body, headers or delay");
                } else if (unknown) {
                    out.add(which + " has no field \"" + field + "\": use " + String.join(", ", ANSWER_FIELDS));
                } else if ("when".equals(field)) {
                    out.add(which + ": when is a method and a path, like GET /customers/{id}" + not);
                } else if ("status".equals(field)) {
                    out.add(which + ": status is a number from 100 to 599" + not);
                } else if ("delay".equals(field)) {
                    out.add(which + ": delay is like 250ms or 2s" + not);
                } else {
                    out.add(which + ": " + field + " is not right");
                }
                continue;
            }
            String allowed = MOCK_FIELDS.getOrDefault(shape.type().id(), "");
            String field = path.startsWith("mock.") ? path.substring(5).split("\\.", 2)[0] : path;
            if (unknown) {
                out.add(mockNoun + " has no field \"" + field + "\"" + (allowed.isEmpty() ? ": it is {}" : ": use " + allowed));
            } else if ("UNREACHABLE".equals(f.kind()) || "mock".equals(path)) {
                out.add(mockNoun + " is a block" + (allowed.isEmpty() ? ": {}" : " (" + allowed + "), or {}"));
            } else if ("messages".equals(field)) {
                out.add("mock messages is a list");
            } else if ("schema".equals(field) || "data".equals(field)) {
                out.add("mock " + field + " is text (SQL)");
            } else {
                out.add("mock " + field + " is not right");
            }
        }
        List<String> result = new ArrayList<>();
        if (!extra.isEmpty()) {
            result.add("a mocked service has no connection details: remove " + String.join(", ", extra) + ", or remove mock");
        }
        missing.remove("type");
        if (!missing.isEmpty()) result.add(noun + " needs " + String.join(", ", missing));
        result.addAll(out);
        return result;
    }

    /** The value at a regl path in {@code document} (list elements by index), or null. */
    static Object valueAt(Object document, String path) {
        if (path == null || path.isEmpty()) return null;
        Object node = document;
        for (String segment : path.split("\\.")) {
            if (node instanceof Map<?, ?> map) node = map.get(segment);
            else if (node instanceof List<?> list && segment.matches("\\d+") && Integer.parseInt(segment) < list.size()) node = list.get(Integer.parseInt(segment));
            else return null;
        }
        return node instanceof Map<?, ?> || node instanceof List<?> ? null : node;
    }
}
