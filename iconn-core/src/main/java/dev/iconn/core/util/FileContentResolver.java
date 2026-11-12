package dev.iconn.core.util;

import dev.iconn.api.ResourceException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Utility to transparently resolve and load content from classpath resources, file paths, or inline text,
 * supporting natural relative path resolution without mandatory "classpath:" prefixes.
 */
public final class FileContentResolver {

    private FileContentResolver() {}

    public static String resolve(String textOrLocation) {
        return resolve("", textOrLocation);
    }

    public static String resolve(String baseLocation, String textOrLocation) {
        if (textOrLocation == null) {
            return null;
        }

        String trimmed = textOrLocation.trim();
        if (trimmed.startsWith("classpath:") || trimmed.startsWith("file:")) {
            return readFile(baseLocation, trimmed);
        }

        if (hasPathOrFileAppearance(trimmed)) {
            String content = tryReadFile(baseLocation, trimmed);
            if (content != null) {
                return content;
            }
        }

        return textOrLocation;
    }

    public static String readFile(String location) {
        return readFile("", location);
    }

    public static String readFile(String baseLocation, String location) {
        String content = tryReadFile(baseLocation, location);
        if (content != null) {
            return content;
        }
        String context = (baseLocation != null && !baseLocation.isBlank()) ? " (resolved relative to base: '" + baseLocation + "')" : "";
        throw new ResourceException("Could not locate or read file at location: '" + location + "'" + context);
    }

    public static String tryReadFile(String location) {
        return tryReadFile("", location);
    }

    public static String tryReadFile(String baseLocation, String location) {
        if (location == null || location.isBlank()) {
            return null;
        }

        String path = location.trim();
        if (path.startsWith("classpath:")) {
            path = path.substring("classpath:".length());
        } else if (path.startsWith("file:")) {
            path = path.substring("file:".length());
        }

        // 1. If baseLocation is provided, try relative path first
        if (baseLocation != null && !baseLocation.isBlank() && !path.startsWith("/")) {
            String combined = baseLocation.endsWith("/") ? baseLocation + path : baseLocation + "/" + path;
            String result = readSingleLocation(combined);
            if (result != null) {
                return result;
            }
        }

        // 2. Try the location directly
        return readSingleLocation(path);
    }

    private static String readSingleLocation(String path) {
        String cleanPath = path;
        if (cleanPath.startsWith("/")) {
            cleanPath = cleanPath.substring(1);
        }

        // Try ClassLoader
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = FileContentResolver.class.getClassLoader();
        }

        InputStream is = cl.getResourceAsStream(cleanPath);
        if (is != null) {
            try (is) {
                return readStream(is);
            } catch (IOException e) {
                throw new ResourceException("Failed to read classpath resource: " + path, e);
            }
        }

        // Try filesystem directly
        File file = new File(path);
        if (file.exists() && file.isFile()) {
            try (InputStream fis = new FileInputStream(file)) {
                return readStream(fis);
            } catch (IOException e) {
                throw new ResourceException("Failed to read file: " + path, e);
            }
        }

        return null;
    }

    private static String readStream(InputStream is) throws IOException {
        byte[] bytes = is.readAllBytes();
        String str = new String(bytes, StandardCharsets.UTF_8);
        if (str.startsWith("\uFEFF")) {
            str = str.substring(1);
        }
        return str;
    }

    private static boolean hasPathOrFileAppearance(String s) {
        if (s.contains("\n") || s.contains("\r") || s.startsWith("{") || s.startsWith("<") || s.startsWith("[")) {
            return false;
        }
        return s.endsWith(".json") || s.endsWith(".xml") || s.endsWith(".sql")
            || s.endsWith(".txt") || s.endsWith(".html") || s.endsWith(".csv")
            || s.contains("/") || s.contains("\\");
    }
}
