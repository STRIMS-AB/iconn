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
class EndToEndIntegrationTest {

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
    @DisplayName("Should run complete multi-resource integration scenario")
    void testCompleteScenario() throws Exception {
        // 1. HTTP stubbing
        customerMock.whenGet("/customers/101")
            .thenOk("{\"id\": 101, \"name\": \"Sarah Connor\"}");

        // 2. Database provisioning
        dbInit.executeScript("""
            CREATE TABLE orders (
                id VARCHAR(50) PRIMARY KEY,
                customer_id INT NOT NULL,
                status VARCHAR(20) NOT NULL
            );
            """);

        // 3. Application workflow simulation:
        // Query external customer API
        HttpResponse response = customerApi.get("/customers/101");
        assertThat(response.isSuccessful()).isTrue();
        assertThat(response.body()).contains("Sarah Connor");

        // Save order to database
        try (Connection conn = database.connection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO orders VALUES ('ord_777', 101, 'COMPLETED')");

            try (ResultSet rs = stmt.executeQuery("SELECT status FROM orders WHERE id = 'ord_777'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("COMPLETED");
            }
        }

        // Send confirmation email
        mail.send(Email.of("orders@acme.com", "sarah@resistance.org", "Order ord_777 Confirmed", "Thank you!"));

        // Verify email delivered to test double
        assertThat(capturedEmails.messages()).hasSize(1);
        Email received = capturedEmails.first();
        assertThat(received.to()).containsExactly("sarah@resistance.org");
        assertThat(received.subject()).isEqualTo("Order ord_777 Confirmed");
    }

    @Test
    @DisplayName("Should support parameter injection in test methods")
    void testParameterInjection(@InjectResource("customerApi") HttpResource api,
                                @InjectResource("customerApi") MockHttp mock) {
        mock.whenGet("/health").thenOk("{\"status\":\"UP\"}");

        HttpResponse resp = api.get("/health");
        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).contains("UP");
    }
}
