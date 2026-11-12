package dev.iconn.api.database;

import java.io.InputStream;

/**
 * Capability interface for executing DDL/DML scripts or raw statements against a database resource.
 */
public interface DatabaseInitializer {

    /**
     * Executes a SQL script loaded from classpath or file.
     *
     * @param scriptContent raw SQL script (statements separated by semicolons)
     */
    void executeScript(String scriptContent);

    /**
     * Executes SQL statements from an input stream.
     */
    void executeScript(InputStream inputStream);

    /**
     * Executes an arbitrary single SQL statement.
     */
    void executeSql(String sql);
}
