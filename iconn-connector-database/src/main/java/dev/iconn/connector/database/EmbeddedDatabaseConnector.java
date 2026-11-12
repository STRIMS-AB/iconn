package dev.iconn.connector.database;

import dev.iconn.api.ResourceDefinition;
import dev.iconn.api.ResourceException;
import dev.iconn.api.database.DatabaseResource;
import dev.iconn.api.spi.Connector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Connector that spins up an embedded in-memory H2 database.
 */
public class EmbeddedDatabaseConnector implements Connector<DatabaseResource> {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedDatabaseConnector.class);

    private EmbeddedDatabaseResourceImpl resource;
    private ResourceDefinition definition;

    @Override
    public DatabaseResource start(ResourceDefinition definition) throws ResourceException {
        this.definition = definition;
        String uniqueId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String dbName = "iconn_" + definition.name().replaceAll("[^a-zA-Z0-9_]", "_") + "_" + uniqueId;

        StringBuilder urlBuilder = new StringBuilder("jdbc:h2:mem:").append(dbName)
            .append(";DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE");

        String mode = definition.getString("mode", null);
        if (mode != null && !mode.isBlank()) {
            urlBuilder.append(";MODE=").append(mode.trim().toUpperCase());
        }

        String username = definition.getString("username", "sa");
        String password = definition.getString("password", "");

        String jdbcUrl = urlBuilder.toString();
        this.resource = new EmbeddedDatabaseResourceImpl(definition.name(), jdbcUrl, username, password);

        // Execute schema if configured
        String schema = definition.getString("schema", null);
        if (schema != null && !schema.isBlank()) {
            executeScriptFromLocation(schema);
        }

        // Execute initial data if configured
        initData();
        runMock();

        this.resource.setCloseHandler(this::reset);

        log.info("Initialized embedded database resource '{}' with JDBC URL: {}", definition.name(), jdbcUrl);
        return resource;
    }

    private void executeScriptFromLocation(String location) {
        String script = dev.iconn.core.util.FileContentResolver.readFile(definition.baseLocation(), location);
        resource.executeScript(script);
    }

    private void initData() {
        Object rawData = definition.config().get("data");
        if (rawData == null) {
            rawData = definition.config().get("seed");
        }
        if (rawData instanceof String s) {
            if (!s.isBlank()) {
                executeScriptFromLocation(s);
            }
        } else if (rawData instanceof java.util.Map<?, ?> map) {
            seedStructuredMap(map);
        }
    }

    @SuppressWarnings("unchecked")
    private void seedStructuredMap(java.util.Map<?, ?> map) {
        for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
            String tableName = String.valueOf(entry.getKey());
            Object val = entry.getValue();
            if (val instanceof java.util.List<?> rows && !rows.isEmpty()) {
                List<Map<String, Object>> rowMaps = new java.util.ArrayList<>();
                for (Object item : rows) {
                    if (item instanceof Map<?, ?> m) {
                        Map<String, Object> row = new java.util.LinkedHashMap<>();
                        m.forEach((k, v) -> row.put(String.valueOf(k), v));
                        rowMaps.add(row);
                    }
                }
                if (!rowMaps.isEmpty()) {
                    createAndPopulateTable(tableName, rowMaps);
                }
            }
        }
    }

    private void createAndPopulateTable(String tableName, List<Map<String, Object>> rows) {
        Map<String, Object> firstRow = rows.get(0);
        StringBuilder createSql = new StringBuilder("CREATE TABLE IF NOT EXISTS ")
                .append(tableName).append(" (");
        int colIdx = 0;
        for (Map.Entry<String, Object> col : firstRow.entrySet()) {
            if (colIdx++ > 0) createSql.append(", ");
            createSql.append(col.getKey()).append(" ").append(inferSqlType(col.getValue()));
        }
        createSql.append(")");
        resource.executeSql(createSql.toString());

        try (Connection conn = resource.connection()) {
            for (Map<String, Object> row : rows) {
                StringBuilder insertSql = new StringBuilder("INSERT INTO ").append(tableName).append(" (");
                StringBuilder valuesSql = new StringBuilder("VALUES (");
                int idx = 0;
                for (String colName : row.keySet()) {
                    if (idx++ > 0) {
                        insertSql.append(", ");
                        valuesSql.append(", ");
                    }
                    insertSql.append(colName);
                    valuesSql.append("?");
                }
                insertSql.append(") ").append(valuesSql).append(")");
                try (java.sql.PreparedStatement ps = conn.prepareStatement(insertSql.toString())) {
                    int p = 1;
                    for (Object v : row.values()) {
                        ps.setObject(p++, v);
                    }
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new ResourceException("Failed to seed table '" + tableName + "' in database '" + definition.name() + "': " + e.getMessage(), e);
        }
    }

    private String inferSqlType(Object val) {
        if (val instanceof Integer || val instanceof Long || val instanceof Short || val instanceof Byte) {
            return "BIGINT";
        }
        if (val instanceof Number) {
            return "DOUBLE";
        }
        if (val instanceof Boolean) {
            return "BOOLEAN";
        }
        return "VARCHAR(255)";
    }

    /**
     * As kfig keeps a mocked database: {@code mock: {schema: SQL, data: SQL}}, each SQL text
     * inline or a file to read.
     */
    private void runMock() {
        Map<String, Object> mock = definition.getMap("mock");
        for (String part : List.of("schema", "data")) {
            if (mock.get(part) instanceof String sql && !sql.isBlank()) {
                resource.executeScript(dev.iconn.core.util.FileContentResolver.resolve(definition.baseLocation(), sql));
            }
        }
    }

    @Override
    public void reset() throws ResourceException {
        if (resource == null) {
            return;
        }
        // An embedded database goes back to exactly what it was given, whether its schema and
        // data are written directly or under `mock`: what a test added or changed is gone
        resource.executeSql("DROP ALL OBJECTS");
        String schema = definition.getString("schema", null);
        if (schema != null && !schema.isBlank()) {
            executeScriptFromLocation(schema);
        }
        initData();
        runMock();
    }

    @Override
    public void stop() throws ResourceException {
        if (resource != null) {
            try (Connection conn = resource.connection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("SHUTDOWN");
            } catch (SQLException e) {
                log.warn("Error shutting down database '{}': {}", definition.name(), e.getMessage());
            }
            resource = null;
        }
    }
}
