package dev.iconn.core.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for interpolating environment variables and system properties within configuration strings.
 * Supports patterns such as {@code ${VAR_NAME}}, {@code ${VAR_NAME:defaultValue}}, and {@code ${VAR_NAME:-defaultValue}}.
 */
public final class EnvVarInterpolator {

    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    private EnvVarInterpolator() {}

    /**
     * Interpolates environment variables and system properties in the given template text.
     *
     * @param text the input string containing potential variable references
     * @return the interpolated text, or null if input was null
     */
    public static String interpolate(String text) {
        if (text == null || !text.contains("${")) {
            return text;
        }

        Matcher matcher = VAR_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String expr = matcher.group(1).trim();
            String varName;
            String defaultValue = null;
            boolean hasDefault = false;

            if (expr.contains(":-")) {
                int idx = expr.indexOf(":-");
                varName = expr.substring(0, idx).trim();
                defaultValue = expr.substring(idx + 2);
                hasDefault = true;
            } else if (expr.contains(":")) {
                int idx = expr.indexOf(':');
                varName = expr.substring(0, idx).trim();
                defaultValue = expr.substring(idx + 1);
                hasDefault = true;
            } else {
                varName = expr;
            }

            String value = System.getenv(varName);
            if (value == null) {
                value = System.getProperty(varName);
            }
            if (value == null && hasDefault) {
                value = defaultValue;
            }
            if (value == null) {
                // Keep verbatim if unresolved
                value = "${" + expr + "}";
            }

            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }

        matcher.appendTail(sb);
        return sb.toString();
    }
}
