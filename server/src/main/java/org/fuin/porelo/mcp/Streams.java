package org.fuin.porelo.mcp;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The streamed calls of one session that are still running, each on a thread of its own, so the
 * session reads on: the next request, and the cancel that ends one of them.
 */
final class Streams implements AutoCloseable {

    /** The most streams one session runs at once; one more is refused, not queued. */
    static final int MAX_STREAMS = 64;

    private final Predicate<String> out;
    private final Map<String, Cancellation> running = new ConcurrentHashMap<>();
    private final List<Thread> threads = new ArrayList<>();

    /**
     * Streams whose messages go out one whole message at a time.
     *
     * @param out Writes one message and says whether it went out; called from several threads, each
     *     call whole. A message that did not go out ends every stream: nobody reads them any more.
     */
    Streams(Predicate<String> out) {
        this.out = out;
    }

    /**
     * Sends one message, whole, before or after another thread's.
     *
     * @param message One JSON-RPC message.
     */
    void send(String message) {
        final boolean written;
        synchronized (out) {
            written = out.test(message);
        }
        if (!written) {
            running.values().forEach(Cancellation::cancel);
        }
    }

    /**
     * Sends one message of a streamed call, unless that call was cancelled: checked under the same
     * lock as the write, so nothing of a call goes out after the reader saw its cancel.
     *
     * @param cancellation The call's.
     * @param message One JSON-RPC message.
     */
    void send(Cancellation cancellation, String message) {
        final boolean written;
        synchronized (out) {
            if (cancellation.cancelled()) {
                return;
            }
            written = out.test(message);
        }
        if (!written) {
            running.values().forEach(Cancellation::cancel);
        }
    }

    /**
     * Runs a streamed call, unless as many run already.
     *
     * @param id The request's id, which a cancel names.
     * @param body The call, given what cancels it.
     * @return {@code false} when the bound is reached, or a stream of this id runs still, and
     *     nothing was started.
     */
    synchronized boolean start(Object id, Consumer<Cancellation> body) {
        threads.removeIf(thread -> !thread.isAlive());
        final String key = key(id);
        // One per id: a second would be one a cancel cannot name, and one the bound does not count.
        if (running.size() >= MAX_STREAMS || running.containsKey(key)) {
            return false;
        }
        final Cancellation cancellation = new Cancellation();
        running.put(key, cancellation);
        threads.add(
                Thread.ofVirtual()
                        .name("mcp-stream")
                        .start(
                                () -> {
                                    try {
                                        body.accept(cancellation);
                                    } finally {
                                        running.remove(key, cancellation);
                                    }
                                }));
        return true;
    }

    /**
     * Cancels the streamed call of a request, if it still runs.
     *
     * @param id The id the cancel names.
     */
    void cancel(Object id) {
        final Cancellation cancellation = running.get(key(id));
        if (cancellation != null) {
            cancellation.cancel();
        }
    }

    /**
     * Cancels every call still running, at the session's end, and waits a moment for each.
     *
     * @throws InterruptedException When interrupted while waiting.
     */
    @Override
    public void close() throws InterruptedException {
        running.values().forEach(Cancellation::cancel);
        final List<Thread> left;
        synchronized (this) {
            left = List.copyOf(threads);
        }
        for (final Thread thread : left) {
            thread.join(5_000);
        }
    }

    /** The same id as a string and as a number are two ids; 1 and 1.0 are one. */
    private static String key(Object id) {
        return id instanceof Number number
                ? "n:" + new BigDecimal(number.toString()).stripTrailingZeros().toPlainString()
                : "s:" + id;
    }
}
