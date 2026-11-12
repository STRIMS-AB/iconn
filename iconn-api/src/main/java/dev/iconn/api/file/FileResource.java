package dev.iconn.api.file;

import dev.iconn.api.Resource;

import java.util.List;
import java.util.Map;

/**
 * Operational resource for local/remote file storage.
 * Supports operations: get, put, list, delete.
 */
public interface FileResource extends Resource {

    /**
     * Retrieves file metadata and content.
     *
     * @param path relative file path
     * @return map with exists, path, size, contentType, content
     */
    Map<String, Object> get(String path);

    /**
     * Stores file content.
     *
     * @param path relative file path
     * @param content String or byte array
     * @return map with ok, path, bytes
     */
    Map<String, Object> put(String path, Object content);

    /**
     * Lists directory entries.
     *
     * @param path directory path
     * @return list of entry maps (path, type, size)
     */
    List<Map<String, Object>> list(String path);

    /**
     * Deletes a file.
     *
     * @param path file path
     * @return map with deleted, path
     */
    Map<String, Object> delete(String path);
}
