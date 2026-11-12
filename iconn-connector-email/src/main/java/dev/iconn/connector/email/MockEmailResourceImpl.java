package dev.iconn.connector.email;

import dev.iconn.api.email.CapturedEmails;
import dev.iconn.api.email.Email;
import dev.iconn.api.email.EmailResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory email resource that captures dispatched emails for inspection during tests.
 */
public class MockEmailResourceImpl implements EmailResource, CapturedEmails {

    private static final Logger log = LoggerFactory.getLogger(MockEmailResourceImpl.class);

    private final String name;
    private final List<Email> mailbox = new CopyOnWriteArrayList<>();

    public MockEmailResourceImpl(String name) {
        this.name = Objects.requireNonNull(name);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void send(Email email) {
        Objects.requireNonNull(email, "email must not be null");
        mailbox.add(email);
        log.info("Captured email in '{}': from={}, to={}, subject='{}'", name, email.from(), email.to(), email.subject());
    }

    @Override
    public List<Email> messages() {
        return Collections.unmodifiableList(new ArrayList<>(mailbox));
    }

    private Runnable closeHandler;

    public void setCloseHandler(Runnable closeHandler) {
        this.closeHandler = closeHandler;
    }

    @Override
    public void close() {
        if (closeHandler != null) {
            closeHandler.run();
        } else {
            clear();
        }
    }

    @Override
    public void clear() {
        mailbox.clear();
    }

    @Override
    public <T> Optional<T> capability(Class<T> capabilityType) {
        if (capabilityType.isInstance(this)) {
            return Optional.of(capabilityType.cast(this));
        }
        return Optional.empty();
    }
}
