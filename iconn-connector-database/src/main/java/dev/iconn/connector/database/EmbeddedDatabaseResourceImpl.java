package dev.iconn.connector.database;

import dev.iconn.api.ResourceException;
import dev.iconn.api.database.DatabaseInitializer;
import dev.iconn.api.database.DatabaseResource;
import org.h2.jdbcx.JdbcDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.Optional;

/**
 * Implementation of {@link DatabaseResource} and {@link DatabaseInitializer} backed by H2.
 */
public class EmbeddedDatabaseResourceImpl implements DatabaseResource, DatabaseInitializer {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedDatabaseResourceImpl.class);

    private final String name;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final JdbcDataSource dataSource;

    public EmbeddedDatabaseResourceImpl(String name, String jdbcUrl, String username, String password) {
        this.name = Objects.requireNonNull(name);
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl);
        this.username = username != null ? username : "sa";
        this.password = password != null ? password : "";

        this.dataSource = new JdbcDataSource();
        this.dataSource.setURL(this.jdbcUrl);
        this.dataSource.setUser(this.username);
        this.dataSource.setPassword(this.password);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public DataSource dataSource() {
        return dataSource;
    }

    private Runnable closeHandler;

    public void setCloseHandler(Runnable closeHandler) {
        this.closeHandler = closeHandler;
    }

    @Override
    public void close() {
        if (closeHandler != null) {
            closeHandler.run();
        }
    }

    @Override
    public String jdbcUrl() {
        return jdbcUrl;
    }

    @Override
    public String username() {
        return username;
    }

    @Override
    public String password() {
        return password;
    }

    @Override
    public <T> Optional<T> capability(Class<T> capabilityType) {
        if (capabilityType.isInstance(this)) {
            return Optional.of(capabilityType.cast(this));
        }
        return Optional.empty();
    }

    @Override
    public void executeScript(String scriptContent) {
        if (scriptContent == null || scriptContent.isBlank()) {
            return;
        }

        try (Connection conn = connection();
             Statement stmt = conn.createStatement()) {
            String[] statements = scriptContent.split(";");
            for (String sql : statements) {
                String trimmed = sql.trim();
                if (!trimmed.isEmpty()) {
                    log.debug("Executing SQL in '{}': {}", name, trimmed);
                    stmt.execute(trimmed);
                }
            }
        } catch (SQLException e) {
            throw new ResourceException("Failed to execute SQL script on database '" + name + "': " + e.getMessage(), e);
        }
    }

    @Override
    public void executeScript(InputStream inputStream) {
        if (inputStream == null) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                // skip comments
                String trimmed = line.trim();
                if (trimmed.startsWith("--") || trimmed.startsWith("//")) {
                    continue;
                }
                sb.append(line).append("\n");
            }
            executeScript(sb.toString());
        } catch (IOException e) {
            throw new ResourceException("Error reading SQL script stream for database '" + name + "': " + e.getMessage(), e);
        }
    }

    @Override
    public void executeSql(String sql) {
        try (Connection conn = connection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            throw new ResourceException("Failed to execute SQL on database '" + name + "': " + e.getMessage(), e);
        }
    }
}
