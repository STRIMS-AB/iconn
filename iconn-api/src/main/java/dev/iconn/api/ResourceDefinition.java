package dev.iconn.api;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable definition of a declared integration resource.
 *
 * @param name the unique resource name
 * @param type the resource kind (e.g. "http", "email", "database")
 * @param provider the backing provider ("mock", "embedded", "real", "container")
 * @param config arbitrary configuration key-value properties
 */
public record ResourceDefinition(
    String name,
    String type,
    String provider,
    Map<String, Object> config,
    String baseLocation
) {
    public ResourceDefinition(String name, String type, String provider, Map<String, Object> config) {
        this(name, type, provider, config, "");
    }

    public ResourceDefinition {
        Objects.requireNonNull(name, "Resource name must not be null");
        Objects.requireNonNull(type, "Resource type must not be null");
        provider = provider == null ? "mock" : provider;
        config = config == null ? Map.of() : Map.copyOf(config);
        baseLocation = baseLocation == null ? "" : baseLocation.trim();
    }

    public <T> Optional<T> getConfig(String key, Class<T> targetType) {
        Object val = config.get(key);
        if (val != null && targetType.isInstance(val)) {
            return Optional.of(targetType.cast(val));
        }
        return Optional.empty();
    }

    public String getString(String key, String defaultValue) {
        Object val = config.get(key);
        return val != null ? val.toString() : defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        Object val = config.get(key);
        if (val instanceof Number n) {
            return n.intValue();
        } else if (val instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Object val = config.get(key);
        if (val instanceof Boolean b) {
            return b;
        } else if (val instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    public java.util.List<Map<String, Object>> getList(String key) {
        Object val = config.get(key);
        if (val instanceof java.util.List<?> list) {
            java.util.List<Map<String, Object>> result = new java.util.ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> converted = new java.util.LinkedHashMap<>();
                    m.forEach((k, v) -> converted.put(String.valueOf(k), v));
                    result.add(converted);
                }
            }
            return result;
        }
        return java.util.List.of();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMap(String key) {
        Object val = config.get(key);
        if (val instanceof Map<?, ?> m) {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            m.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        return Map.of();
    }
}
