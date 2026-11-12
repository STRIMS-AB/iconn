package dev.iconn.core.file;

import dev.iconn.api.ResourceDefinition;
import dev.iconn.api.ResourceException;
import dev.iconn.api.file.FileResource;
import dev.iconn.api.spi.Connector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Connector that provides local file storage rooted at a configured directory.
 */
public class LocalFileConnector implements Connector<FileResource> {

    @Override
    public FileResource start(ResourceDefinition definition) throws ResourceException {
        String dir = definition.getString("dir", definition.getString("path", null));
        if (dir == null || dir.isBlank()) {
            throw new ResourceException("file resource '" + definition.name() + "' requires a 'dir'");
        }

        Path root = Path.of(dir);
        if (!root.isAbsolute() && definition.baseLocation() != null && !definition.baseLocation().isBlank()) {
            root = Path.of(definition.baseLocation()).resolve(root);
        }
        root = root.toAbsolutePath().normalize();

        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new ResourceException("Failed to create file resource root dir: " + root, e);
        }

        return new LocalFileResourceImpl(definition.name(), root);
    }

    @Override
    public void stop() throws ResourceException {
        // No background processes or network sockets to stop
    }

    private static class LocalFileResourceImpl implements FileResource {
        private final String name;
        private final Path root;

        LocalFileResourceImpl(String name, Path root) {
            this.name = name;
            this.root = root;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Map<String, Object> get(String path) {
            Path target = resolve(path);
            if (target == null || !Files.exists(target) || Files.isDirectory(target)) {
                return Map.of("exists", false, "path", relative(target != null ? target : root));
            }
            try {
                byte[] bytes = Files.readAllBytes(target);
                String contentType = Files.probeContentType(target);
                return Map.of(
                        "exists", true,
                        "path", relative(target),
                        "size", bytes.length,
                        "contentType", contentType != null ? contentType : "application/octet-stream",
                        "content", textOrBase64(bytes)
                );
            } catch (IOException e) {
                throw new ResourceException("file resource: cannot read '" + relative(target) + "': " + e.getMessage(), e);
            }
        }

        @Override
        public Map<String, Object> put(String path, Object content) {
            Path target = resolve(path);
            try {
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                byte[] bytes = toBytes(content);
                Files.write(target, bytes);
                return Map.of("ok", true, "path", relative(target), "bytes", bytes.length);
            } catch (IOException e) {
                throw new ResourceException("file resource: cannot write '" + relative(target) + "': " + e.getMessage(), e);
            }
        }

        @Override
        public List<Map<String, Object>> list(String path) {
            Path dir = resolve(path);
            if (dir == null || !Files.isDirectory(dir)) {
                return List.of();
            }
            List<Map<String, Object>> entries = new ArrayList<>();
            try (var stream = Files.walk(dir)) {
                stream.sorted().forEach(p -> {
                    if (p.equals(dir)) return;
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("path", relative(p));
                    entry.put("type", Files.isDirectory(p) ? "dir" : "file");
                    try {
                        entry.put("size", Files.isDirectory(p) ? 0 : Files.size(p));
                    } catch (IOException ignored) {
                        entry.put("size", 0);
                    }
                    entries.add(entry);
                });
            } catch (IOException e) {
                throw new ResourceException("file resource: cannot list '" + relative(dir) + "': " + e.getMessage(), e);
            }
            return entries;
        }

        @Override
        public Map<String, Object> delete(String path) {
            Path target = resolve(path);
            boolean deleted = false;
            if (target != null && !Files.isDirectory(target)) {
                try {
                    deleted = Files.deleteIfExists(target);
                } catch (IOException ignored) {
                    deleted = false;
                }
            }
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("deleted", deleted);
            res.put("path", target != null ? relative(target) : null);
            return res;
        }

        private Path resolve(String pathArg) {
            if (pathArg == null) {
                return null;
            }
            Path target = root.resolve(pathArg).normalize();
            if (!target.startsWith(root)) {
                throw new ResourceException("file resource: path '" + pathArg + "' escapes the configured dir");
            }
            return target;
        }

        private String relative(Path p) {
            return root.relativize(p).toString().replace('\\', '/');
        }

        private byte[] toBytes(Object content) {
            if (content == null) {
                return new byte[0];
            }
            if (content instanceof byte[] b) {
                return b;
            }
            if (content instanceof String s) {
                return s.getBytes(StandardCharsets.UTF_8);
            }
            // For complex objects, serialize using Jackson (already in iconn-core)
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                return mapper.writeValueAsBytes(content);
            } catch (Exception e) {
                return String.valueOf(content).getBytes(StandardCharsets.UTF_8);
            }
        }

        private String textOrBase64(byte[] bytes) {
            String utf8 = new String(bytes, StandardCharsets.UTF_8);
            if (utf8.indexOf('\uFFFD') < 0) {
                return utf8;
            }
            return Base64.getEncoder().encodeToString(bytes);
        }
    }
}
