package dev.iconn.api.cache;

import dev.iconn.api.Resource;

import java.util.Optional;
import java.util.Set;

/**
 * A cache: short-lived values under keys.
 */
public interface CacheResource extends Resource {

    /** The value under {@code key}, if there is one. */
    Optional<Object> get(String key);

    /** Puts {@code value} under {@code key}, replacing what was there. */
    void put(String key, Object value);

    /** Removes {@code key}; whether it was there. */
    boolean delete(String key);

    /** The keys held now. */
    Set<String> keys();
}
