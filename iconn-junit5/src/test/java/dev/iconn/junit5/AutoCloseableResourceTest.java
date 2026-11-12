package dev.iconn.junit5;

import dev.iconn.api.database.DatabaseInitializer;
import dev.iconn.api.database.DatabaseResource;
import dev.iconn.api.email.CapturedEmails;
import dev.iconn.api.email.Email;
import dev.iconn.api.email.EmailResource;
import dev.iconn.api.http.HttpResponse;
import dev.iconn.api.http.HttpResource;
import dev.iconn.api.http.MockHttp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationResources("integration-test.yaml")
class AutoCloseableResourceTest {

    @InjectResource("customerApi")
    private HttpResource customerApi;

    @InjectResource("customerApi")
    private MockHttp customerMock;

    @InjectResource("mail")
    private EmailResource mail;

    @InjectResource("mail")
    private CapturedEmails capturedEmails;

    @InjectResource("database")
    private DatabaseResource database;

    @InjectResource("database")
    private DatabaseInitializer dbInit;

    @Test
    @DisplayName("Try-with-resources on HttpResource should automatically reinitialize mock state upon scope exit")
    void shouldReinitializeHttpResourceOnTryClose() {
        try (HttpResource client = customerApi) {
            customerMock.whenGet("/temp-endpoint").thenOk("{\"status\":\"TEMP\"}");

            HttpResponse resp = client.get("/temp-endpoint");
            assertThat(resp.status()).isEqualTo(200);
            assertThat(customerMock.receivedRequests()).hasSize(1);
        } // client.close() called automatically here!

        // Verifying that HTTP resource was reinitialized:
        assertThat(customerMock.receivedRequests()).isEmpty();

        // The dynamic stub was cleared on close
        HttpResponse respAfter = customerApi.get("/temp-endpoint");
        assertThat(respAfter.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("Try-with-resources on EmailResource should automatically clear and reinitialize mailbox upon scope exit")
    void shouldClearEmailResourceOnTryClose() {
        try (EmailResource mailer = mail) {
            mailer.send(Email.of("test@domain.com", "user@domain.com", "Test Subject", "Test Body"));
            assertThat(capturedEmails.messages()).hasSize(1);
        } // mailer.close() called automatically here!

        // Verifying that mailbox was reinitialized:
        assertThat(capturedEmails.messages()).isEmpty();
    }

    @Test
    @DisplayName("Try-with-resources on DatabaseResource should automatically reset state upon scope exit")
    void shouldResetDatabaseResourceOnTryClose() throws Exception {
        dbInit.executeScript("CREATE TABLE test_items (id INT PRIMARY KEY);");

        try (DatabaseResource db = database) {
            try (Connection conn = db.connection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO test_items VALUES (100);");
            }
        } // db.close() called automatically here!

        // The database is back to what it was given: still usable, and without the table
        // and the row the test added
        try (Connection conn = database.connection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT count(*) FROM information_schema.tables WHERE table_name = 'TEST_ITEMS'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isZero();
        }
    }
}
