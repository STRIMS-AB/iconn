package dev.iconn.connector.database;

import dev.iconn.api.ResourceDefinition;
import dev.iconn.api.database.DatabaseInitializer;
import dev.iconn.api.database.DatabaseResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddedDatabaseConnectorTest {

    private EmbeddedDatabaseConnector connector;
    private DatabaseResource db;

    @BeforeEach
    void setUp() {
        connector = new EmbeddedDatabaseConnector();
        ResourceDefinition def = new ResourceDefinition("appDb", "database", "embedded", Map.of());
        db = connector.start(def);
    }

    @AfterEach
    void tearDown() {
        if (connector != null) {
            connector.stop();
        }
    }

    @Test
    void shouldExecuteSchemaAndQueryData() throws Exception {
        DatabaseInitializer init = db.capability(DatabaseInitializer.class).orElseThrow();
        init.executeScript("""
            CREATE TABLE customers (
                id BIGINT PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                email VARCHAR(100) NOT NULL
            );
            INSERT INTO customers VALUES (1, 'Alice Smith', 'alice@example.com');
            INSERT INTO customers VALUES (2, 'Bob Jones', 'bob@example.com');
            """);

        try (Connection conn = db.connection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) FROM customers")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(2);
        }
    }

    @Test
    void resetPutsADatabaseBackToWhatItWasGiven() throws Exception {
        EmbeddedDatabaseConnector seeded = new EmbeddedDatabaseConnector();
        // schema and rows written directly, not under `mock`
        DatabaseResource orders = seeded.start(new ResourceDefinition("ordersDb", "database", "embedded", Map.of(
                "data", Map.of("orders", java.util.List.of(
                        Map.of("id", "ord_1", "status", "pending"),
                        Map.of("id", "ord_2", "status", "shipped"))))));
        try {
            assertThat(count(orders, "orders")).isEqualTo(2);

            try (Connection conn = orders.connection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO orders (id, status) VALUES ('ord_9', 'pending')");
                stmt.execute("CREATE TABLE notes (text VARCHAR(50))");
            }
            assertThat(count(orders, "orders")).isEqualTo(3);

            seeded.reset();

            // the two rows it was given: what the test added is gone, nothing is there twice
            assertThat(count(orders, "orders")).isEqualTo(2);
            try (Connection conn = orders.connection(); Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT count(*) FROM information_schema.tables WHERE table_name = 'NOTES'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isZero();
            }
        } finally {
            seeded.stop();
        }
    }

    private static int count(DatabaseResource database, String table) throws Exception {
        try (Connection conn = database.connection(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
