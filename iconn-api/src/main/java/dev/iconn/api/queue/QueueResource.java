package dev.iconn.api.queue;

import dev.iconn.api.Resource;

import java.util.Map;

/**
 * Operational resource representing a message queue.
 * Supports operations: send (enqueue), receive (dequeue), peek.
 */
public interface QueueResource extends Resource {

    /**
     * Enqueues a message payload.
     *
     * @param message payload
     * @return map with ok, queue, size
     */
    Map<String, Object> send(Object message);

    /**
     * Dequeues a message payload, or null if empty.
     *
     * @return dequeued payload, or null
     */
    Object receive();

    /**
     * Peeks at the head of the queue without removing it, or null if empty.
     *
     * @return head payload, or null
     */
    Object peek();

    /**
     * Current queue size.
     */
    int size();

    /**
     * Clears all messages from this queue.
     */
    void clear();
}
