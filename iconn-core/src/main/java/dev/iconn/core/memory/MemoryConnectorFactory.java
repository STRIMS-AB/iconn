package dev.iconn.core.memory;

import dev.iconn.api.Resource;
import dev.iconn.api.ResourceDefinition;
import dev.iconn.api.ResourceException;
import dev.iconn.api.cache.CacheResource;
import dev.iconn.api.file.FileResource;
import dev.iconn.api.spi.Connector;
import dev.iconn.api.spi.ConnectorFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mocked storage and caches, held in memory: each starts from its {@code mock} (as kfig keeps
 * it: {@code mock: {files: {path: text}}}, {@code mock: {entries: {key: value}}}) and goes back
 * to it on {@code reset()}.
 */
public final class MemoryConnectorFactory implements ConnectorFactory {

    @Override
    public boolean supports(String type, String provider) {
        return ("storage".equalsIgnoreCase(type) || "cache".equalsIgnoreCase(type))
                && ("mock".equalsIgnoreCase(provider) || "memory".equalsIgnoreCase(provider) || "embedded".equalsIgnoreCase(provider));
    }

    @Override
    public Connector<? extends Resource> create(String type, String provider) {
        return "storage".equalsIgnoreCase(type) ? new StorageConnector() : new CacheConnector();
    }

    static final class StorageConnector implements Connector<FileResource> {
        private Files files;

        @Override
        public FileResource start(ResourceDefinition definition) throws ResourceException {
            Map<String, String> initial = new LinkedHashMap<>();
            if (definition.getMap("mock").get("files") instanceof Map<?, ?> f) {
                f.forEach((k, v) -> initial.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
            }
            files = new Files(definition.name(), initial);
            return files;
        }

        @Override public void reset() { if (files != null) files.reset(); }
        @Override public void stop() { }
    }

    static final class CacheConnector implements Connector<CacheResource> {
        private Cache cache;

        @Override
        public CacheResource start(ResourceDefinition definition) throws ResourceException {
            Map<String, Object> initial = new LinkedHashMap<>();
            if (definition.getMap("mock").get("entries") instanceof Map<?, ?> e) {
                e.forEach((k, v) -> { if (v != null) initial.put(String.valueOf(k), v); });
            }
            cache = new Cache(definition.name(), initial);
            return cache;
        }

        @Override public void reset() { if (cache != null) cache.reset(); }
        @Override public void stop() { }
    }

    /** A storage service: files by path, as text. */
    static final class Files implements FileResource {
        private final String name;
        private final Map<String, String> initial;
        private final Map<String, String> files = new ConcurrentHashMap<>();

        Files(String name, Map<String, String> initial) {
            this.name = name;
            this.initial = Map.copyOf(initial);
            reset();
        }

        @Override public String name() { return name; }

        private static String clean(String path) {
            String p = path == null ? "" : path.trim();
            while (p.startsWith("/")) p = p.substring(1);
            return p;
        }

        @Override
        public Map<String, Object> get(String path) {
            String p = clean(path);
            String content = files.get(p);
            if (content == null) return Map.of("exists", false, "path", p);
            return Map.of("exists", true, "path", p, "size", content.getBytes(StandardCharsets.UTF_8).length,
                    "contentType", "text/plain", "content", content);
        }

        @Override
        public Map<String, Object> put(String path, Object content) {
            String p = clean(path);
            String text = content instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(content);
            files.put(p, text);
            return Map.of("ok", true, "path", p, "bytes", text.getBytes(StandardCharsets.UTF_8).length);
        }

        @Override
        public List<Map<String, Object>> list(String path) {
            String prefix = clean(path);
            if (!prefix.isEmpty() && !prefix.endsWith("/")) prefix += "/";
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map.Entry<String, String> e : new TreeMap<>(files).entrySet()) {
                if (!e.getKey().startsWith(prefix)) continue;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("path", e.getKey());
                entry.put("type", "file");
                entry.put("size", e.getValue().getBytes(StandardCharsets.UTF_8).length);
                out.add(entry);
            }
            return out;
        }

        @Override
        public Map<String, Object> delete(String path) {
            String p = clean(path);
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("deleted", files.remove(p) != null);
            res.put("path", p);
            return res;
        }

        @Override
        public void reset() {
            files.clear();
            files.putAll(initial);
        }
    }

    /** A cache: values by key. */
    static final class Cache implements CacheResource {
        private final String name;
        private final Map<String, Object> initial;
        private final Map<String, Object> entries = new ConcurrentHashMap<>();

        Cache(String name, Map<String, Object> initial) {
            this.name = name;
            this.initial = new LinkedHashMap<>(initial);
            reset();
        }

        @Override public String name() { return name; }
        @Override public Optional<Object> get(String key) { return Optional.ofNullable(entries.get(key)); }
        @Override public void put(String key, Object value) { entries.put(key, value); }
        @Override public boolean delete(String key) { return entries.remove(key) != null; }
        @Override public Set<String> keys() { return Set.copyOf(entries.keySet()); }

        @Override
        public void reset() {
            entries.clear();
            entries.putAll(initial);
        }
    }
}
