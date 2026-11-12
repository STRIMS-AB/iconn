package dev.iconn.api.database;

import dev.iconn.api.Resource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Operational resource representing a relational database.
 */
public interface DatabaseResource extends Resource {

    /**
     * Managed DataSource connected to the database.
     */
    DataSource dataSource();

    /**
     * Acquires a new JDBC connection from the managed DataSource.
     */
    default Connection connection() throws SQLException {
        return dataSource().getConnection();
    }

    /**
     * JDBC URL of this database.
     */
    String jdbcUrl();

    /**
     * Database username.
     */
    String username();

    /**
     * Database password.
     */
    String password();
}
