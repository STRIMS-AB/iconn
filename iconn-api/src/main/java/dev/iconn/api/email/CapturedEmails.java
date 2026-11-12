package dev.iconn.api.email;

import java.util.List;

/**
 * Capability interface for inspecting emails sent to a mock or test mail server.
 */
public interface CapturedEmails {

    /**
     * All messages captured by the mock mail server in order of receipt.
     */
    List<Email> messages();

    /**
     * Convenience method to retrieve the first captured email or throw if none.
     */
    default Email first() {
        List<Email> all = messages();
        if (all.isEmpty()) {
            throw new IllegalStateException("No emails have been captured yet");
        }
        return all.get(0);
    }

    /**
     * Convenience method to retrieve the last captured email or throw if none.
     */
    default Email last() {
        List<Email> all = messages();
        if (all.isEmpty()) {
            throw new IllegalStateException("No emails have been captured yet");
        }
        return all.get(all.size() - 1);
    }

    /**
     * Clears all captured emails.
     */
    void clear();
}
