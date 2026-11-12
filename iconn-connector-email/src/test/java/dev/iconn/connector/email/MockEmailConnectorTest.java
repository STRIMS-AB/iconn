package dev.iconn.connector.email;

import dev.iconn.api.ResourceDefinition;
import dev.iconn.api.email.CapturedEmails;
import dev.iconn.api.email.Email;
import dev.iconn.api.email.EmailResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MockEmailConnectorTest {

    private MockEmailConnector connector;
    private EmailResource emailResource;
    private CapturedEmails capturedEmails;

    @BeforeEach
    void setUp() {
        connector = new MockEmailConnector();
        ResourceDefinition def = new ResourceDefinition("notifications", "email", "mock", Map.of());
        emailResource = connector.start(def);
        capturedEmails = emailResource.capability(CapturedEmails.class).orElseThrow();
    }

    @AfterEach
    void tearDown() {
        if (connector != null) {
            connector.stop();
        }
    }

    @Test
    void shouldCaptureSentEmails() {
        Email email = Email.builder()
            .from("orders@example.com")
            .to("customer@example.com")
            .subject("Order Confirmation #1234")
            .body("Thank you for your purchase!")
            .build();

        emailResource.send(email);

        assertThat(capturedEmails.messages()).hasSize(1);
        Email received = capturedEmails.first();
        assertThat(received.from()).isEqualTo("orders@example.com");
        assertThat(received.to()).containsExactly("customer@example.com");
        assertThat(received.subject()).isEqualTo("Order Confirmation #1234");
        assertThat(received.body()).isEqualTo("Thank you for your purchase!");
    }

    @Test
    void shouldClearCapturedEmailsOnReset() {
        emailResource.send(Email.of("a@b.com", "c@d.com", "Test", "Content"));
        assertThat(capturedEmails.messages()).hasSize(1);

        connector.reset();
        assertThat(capturedEmails.messages()).isEmpty();
    }
}
