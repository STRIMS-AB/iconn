package dev.iconn.core.queue;

import dev.iconn.api.ResourceDefinition;
import dev.iconn.api.ResourceException;
import dev.iconn.api.queue.QueueResource;
import dev.iconn.api.spi.Connector;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Connector providing in-memory FIFO queues.
 * Queues are identified by resource name and shared across the JVM so that
 * senders and receivers across scripts can cooperate.
 */
public class InMemoryQueueConnector implements Connector<QueueResource> {

    private static final Map<String, ConcurrentLinkedQueue<Object>> QUEUES = new ConcurrentHashMap<>();

    private String name;
    private java.util.List<Object> initial = java.util.List.of();

    @Override
    public QueueResource start(ResourceDefinition definition) throws ResourceException {
        name = definition.name();
        // As kfig keeps a mocked queue: mock: {messages: [...]}, the messages waiting at the start
        Object messages = definition.getMap("mock").get("messages");
        if (messages instanceof java.util.List<?> list) initial = new java.util.ArrayList<>(list);
        if (definition.config().get("mock") instanceof Map<?, ?>) {
            // A mock starts from exactly its mock, whatever a queue of the same name held before
            ConcurrentLinkedQueue<Object> fresh = new ConcurrentLinkedQueue<>(initial);
            QUEUES.put(name, fresh);
        }
        return new InMemoryQueueResourceImpl(name);
    }

    @Override
    public void stop() throws ResourceException {
        // In-memory queues do not hold external resources
    }

    @Override
    public void reset() throws ResourceException {
        if (name == null) {
            return;
        }
        // Only this queue: the others have their own connector, which resets them
        ConcurrentLinkedQueue<Object> queue = QUEUES.computeIfAbsent(name, k -> new ConcurrentLinkedQueue<>());
        queue.clear();
        queue.addAll(initial);
    }

    private static class InMemoryQueueResourceImpl implements QueueResource {
        private final String name;

        InMemoryQueueResourceImpl(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        private ConcurrentLinkedQueue<Object> getQueue() {
            return QUEUES.computeIfAbsent(name, k -> new ConcurrentLinkedQueue<>());
        }

        @Override
        public Map<String, Object> send(Object message) {
            ConcurrentLinkedQueue<Object> queue = getQueue();
            queue.add(message);
            return Map.of("ok", true, "queue", name, "size", queue.size());
        }

        @Override
        public Object receive() {
            return getQueue().poll();
        }

        @Override
        public Object peek() {
            return getQueue().peek();
        }

        @Override
        public int size() {
            return getQueue().size();
        }

        @Override
        public void clear() {
            getQueue().clear();
        }

        @Override
        public void reset() {
            clear();
        }
    }
}
