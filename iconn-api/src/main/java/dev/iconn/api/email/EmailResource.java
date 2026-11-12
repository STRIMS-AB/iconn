package dev.iconn.api.email;

import dev.iconn.api.Resource;

/**
 * Operational resource for dispatching emails.
 */
public interface EmailResource extends Resource {

    /**
     * Sends an email message.
     */
    void send(Email email);
}
