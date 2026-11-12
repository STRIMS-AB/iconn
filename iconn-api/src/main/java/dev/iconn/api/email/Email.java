package dev.iconn.api.email;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable email message abstraction.
 */
public record Email(
    String from,
    List<String> to,
    String subject,
    String body
) {
    public Email {
        Objects.requireNonNull(from, "Email from address must not be null");
        Objects.requireNonNull(subject, "Email subject must not be null");
        to = to == null ? List.of() : Collections.unmodifiableList(List.copyOf(to));
    }

    public static Email of(String from, String to, String subject, String body) {
        return new Email(from, List.of(to), subject, body);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String from;
        private List<String> to = List.of();
        private String subject = "";
        private String body = "";

        public Builder from(String from) {
            this.from = from;
            return this;
        }

        public Builder to(String... recipients) {
            this.to = List.of(recipients);
            return this;
        }

        public Builder to(List<String> recipients) {
            this.to = recipients;
            return this;
        }

        public Builder subject(String subject) {
            this.subject = subject;
            return this;
        }

        public Builder body(String body) {
            this.body = body;
            return this;
        }

        public Email build() {
            return new Email(from, to, subject, body);
        }
    }
}
