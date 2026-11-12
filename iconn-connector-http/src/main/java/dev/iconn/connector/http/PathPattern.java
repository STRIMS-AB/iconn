package dev.iconn.connector.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiled HTTP URL path pattern supporting named path parameters (e.g. {@code /api/v1/customers/{customerId}}
 * or {@code /dealers/{dealerId}/booking-engine}), segment wildcards ({@code *}), and multi-segment
 * wildcards ({@code **}).
 */
public final class PathPattern implements Comparable<PathPattern> {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\{([a-zA-Z0-9_-]+)(?::([^}]+))?\\}|\\*\\*|\\*");

    private final String rawPattern;
    private final String normalizedPattern;
    private final boolean isExact;
    private final Pattern compiledRegex;
    private final List<String> parameterNames;
    private final int specificity;

    private PathPattern(String rawPattern) {
        this.rawPattern = Objects.requireNonNull(rawPattern, "pattern must not be null");
        this.normalizedPattern = normalize(rawPattern);

        boolean hasTokens = rawPattern.contains("{") || rawPattern.contains("*");
        if (!hasTokens) {
            this.isExact = true;
            this.compiledRegex = null;
            this.parameterNames = List.of();
            this.specificity = 1000 + countSegments(normalizedPattern) * 10;
        } else {
            this.isExact = false;
            List<String> params = new ArrayList<>();
            Matcher matcher = TOKEN_PATTERN.matcher(normalizedPattern);
            StringBuilder regexBuilder = new StringBuilder("^");
            int lastIndex = 0;
            int staticCount = 0;
            int paramCount = 0;
            int wildcardCount = 0;

            while (matcher.find()) {
                String literal = normalizedPattern.substring(lastIndex, matcher.start());
                if (!literal.isEmpty()) {
                    regexBuilder.append(Pattern.quote(literal));
                    staticCount += countSegments(literal);
                }

                String token = matcher.group();
                if ("**".equals(token)) {
                    regexBuilder.append(".*");
                    wildcardCount += 10;
                } else if ("*".equals(token)) {
                    regexBuilder.append("[^/]+");
                    wildcardCount += 1;
                } else {
                    String name = matcher.group(1);
                    String customRegex = matcher.group(2);
                    params.add(name);
                    if (customRegex != null && !customRegex.isBlank()) {
                        regexBuilder.append("(").append(customRegex).append(")");
                    } else {
                        regexBuilder.append("([^/]+)");
                    }
                    paramCount++;
                }
                lastIndex = matcher.end();
            }

            if (lastIndex < normalizedPattern.length()) {
                String literal = normalizedPattern.substring(lastIndex);
                regexBuilder.append(Pattern.quote(literal));
                staticCount += countSegments(literal);
            }

            regexBuilder.append("/?$");

            this.compiledRegex = Pattern.compile(regexBuilder.toString());
            this.parameterNames = Collections.unmodifiableList(params);
            this.specificity = (staticCount * 100) - (paramCount * 10) - (wildcardCount * 20);
        }
    }

    /**
     * Compiles a path pattern into a matcher.
     */
    public static PathPattern compile(String pattern) {
        return new PathPattern(pattern);
    }

    /**
     * Normalizes a path string by ensuring a leading slash, removing query parameters,
     * and stripping redundant trailing slashes.
     */
    public static String normalize(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String clean = path.trim();
        int queryIdx = clean.indexOf('?');
        if (queryIdx >= 0) {
            clean = clean.substring(0, queryIdx);
        }
        if (!clean.startsWith("/")) {
            clean = "/" + clean;
        }
        if (clean.length() > 1 && clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean;
    }

    private static int countSegments(String path) {
        int count = 0;
        for (char c : path.toCharArray()) {
            if (c == '/') count++;
        }
        return count;
    }

    /**
     * Attempts to match the incoming request path against this pattern.
     *
     * @param requestPath the request path (with or without query strings)
     * @return an Optional containing the map of extracted parameter names to values if matched, otherwise empty
     */
    public Optional<Map<String, String>> match(String requestPath) {
        String cleanReqPath = normalize(requestPath);

        if (isExact) {
            if (normalizedPattern.equals(cleanReqPath)) {
                return Optional.of(Map.of());
            }
            return Optional.empty();
        }

        Matcher matcher = compiledRegex.matcher(cleanReqPath);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        if (parameterNames.isEmpty()) {
            return Optional.of(Map.of());
        }

        Map<String, String> extracted = new LinkedHashMap<>();
        for (int i = 0; i < parameterNames.size(); i++) {
            String paramName = parameterNames.get(i);
            String val = matcher.group(i + 1);
            extracted.put(paramName, val);
        }
        return Optional.of(Collections.unmodifiableMap(extracted));
    }

    public String rawPattern() {
        return rawPattern;
    }

    public boolean isExact() {
        return isExact;
    }

    public int specificity() {
        return specificity;
    }

    public List<String> parameterNames() {
        return parameterNames;
    }

    @Override
    public int compareTo(PathPattern other) {
        return Integer.compare(other.specificity, this.specificity);
    }

    @Override
    public String toString() {
        return rawPattern;
    }
}
