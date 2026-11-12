package dev.iconn.core.util;

import java.time.Duration;

/**
 * Utility to parse duration strings from YAML configuration (e.g. "50ms", "2s", "500").
 */
public final class DurationParser {

    private DurationParser() {}

    public static Duration parse(Object obj) {
        if (obj == null) {
            return Duration.ZERO;
        }

        if (obj instanceof Duration d) {
            return d;
        }

        if (obj instanceof Number n) {
            return Duration.ofMillis(n.longValue());
        }

        String str = obj.toString().trim().toLowerCase();
        if (str.isEmpty() || str.equals("0")) {
            return Duration.ZERO;
        }

        if (str.endsWith("ms")) {
            return Duration.ofMillis(Long.parseLong(str.substring(0, str.length() - 2).trim()));
        } else if (str.endsWith("s")) {
            return Duration.ofSeconds(Long.parseLong(str.substring(0, str.length() - 1).trim()));
        } else if (str.endsWith("m")) {
            return Duration.ofMinutes(Long.parseLong(str.substring(0, str.length() - 1).trim()));
        } else {
            return Duration.ofMillis(Long.parseLong(str));
        }
    }
}
