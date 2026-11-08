package org.fuin.porelo.mcp;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The streamed calls of one transport that are still running, each on a thread of its own, so the
 * session reads on: the next request, and the cancel that ends one of them. Over stdio one {@code
 * Streams} serves the whole session; over HTTP each connection has one, and all of a socket's share
 * one {@link Registry}, so a cancel on one connection finds a stream on another.
 */
final class Streams implements AutoCloseable {

    /** The most streams that run at once; one more is refused, not queued. */
    static final int MAX_STREAMS = 64;

    private final Predicate<String> out;
    private final Registry registry;
    private final boolean carries;
    private final Runnable onStart;
    private final Runnable onCancel;
    private final Set<Cancellation> here = ConcurrentHashMap.newKeySet();
    private final Set<Thread> threads = ConcurrentHashMap.newKeySet();
    private volatile boolean started;

    /**
     * The streams of one transport that carries them all, as stdio does.
     *
     * @param out Writes one message and says whether it went out; called from several threads, each
     *     call whole. A message that did not go out ends every stream: nobody reads them any more.
     */
    Streams(Predicate<String> out) {
        this(out, new Registry(), true, () -> {}, () -> {});
    }

    /**
     * The streams of one request among others, as over HTTP.
     *
     * @param out As above.
     * @param registry What the streams of all requests share: ids, the bound, cancels.
     * @param carries Whether this transport can carry a stream; when not, a call is answered once.
     * @param onStart Run once, before the first stream's thread starts: the answer's head, say.
     * @param onCancel Run when a stream here is cancelled, besides closing its daemon connection.
     */
    Streams(
            Predicate<String> out,
            Registry registry,
            boolean carries,
            Runnable onStart,
            Runnable onCancel) {
        this.out = out;
        this.registry = registry;
        this.carries = carries;
        this.onStart = onStart;
        this.onCancel = onCancel;
    }

    /**
     * Tells whether a call may stream here.
     *
     * @return {@code false} when the client takes one answer alone.
     */
    boolean carries() {
        return carries;
    }

    /**
     * Tells whether a stream was ever started here.
     *
     * @return {@code true} once {@link #start} started one.
     */
    boolean started() {
        return started;
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
            cancelAll();
        }
    }

    /**
     * Sends one message of a streamed call, unless that call was cancelled. The check is made under
     * the write's lock, so a cancel seen before a message is written stops it; one that arrives
     * while a message is being written does not unsend it, which MCP allows.
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
            cancelAll();
        }
    }

    /**
     * Runs a streamed call, unless as many run already or one runs under the same id. A cancel that
     * named the id a moment before is not lost: the call starts cancelled, and ends at once.
     *
     * @param id The request's id, which a cancel names.
     * @param body The call, given what cancels it.
     * @return {@code false} when nothing was started.
     */
    synchronized boolean start(Object id, Consumer<Cancellation> body) {
        final String key = key(id);
        final Cancellation cancellation = new Cancellation();
        if (!registry.reserve(key, cancellation)) {
            return false;
        }
        here.add(cancellation);
        cancellation.onCancel(onCancel);
        if (!started) {
            started = true;
            onStart.run();
        }
        final Thread thread =
                Thread.ofVirtual()
                        .name("mcp-stream")
                        .unstarted(
                                () -> {
                                    try {
                                        body.accept(cancellation);
                                    } finally {
                                        registry.release(key, cancellation);
                                        here.remove(cancellation);
                                        threads.remove(Thread.currentThread());
                                    }
                                });
        threads.add(thread);
        thread.start();
        return true;
    }

    /**
     * Cancels the streamed call of a request, wherever it runs on this registry.
     *
     * @param id The id the cancel names.
     */
    void cancel(Object id) {
        registry.cancel(key(id));
    }

    /** Cancels every call started here and still running, without waiting. */
    void cancelAll() {
        here.forEach(Cancellation::cancel);
    }

    /**
     * Waits until every call started here has ended, without cancelling any.
     *
     * @throws InterruptedException When interrupted while waiting.
     */
    void awaitAll() throws InterruptedException {
        for (final Thread thread : List.copyOf(threads)) {
            thread.join();
        }
    }

    /**
     * Cancels every call started here, at the session's end, and waits a moment for each.
     *
     * @throws InterruptedException When interrupted while waiting.
     */
    @Override
    public void close() throws InterruptedException {
        cancelAll();
        for (final Thread thread : List.copyOf(threads)) {
            thread.join(5_000);
        }
    }

    /** The same id as a string and as a number are two ids; 1 and 1.0 are one. */
    static String key(Object id) {
        return id instanceof Number number
                ? "n:" + new BigDecimal(number.toString()).stripTrailingZeros().toPlainString()
                : "s:" + id;
    }

    /**
     * What the streams of one socket share: the calls running under each id, the bound on them, and
     * the cancels that came a moment before their call.
     */
    static final class Registry {

        /** How long a cancel naming no running call waits for that call to start. */
        private static final long EARLY_NANOS = 10_000_000_000L;

        private final Map<String, Cancellation> running = new ConcurrentHashMap<>();
        private final Map<String, Long> early = new LinkedHashMap<>();
        private boolean shut;

        synchronized boolean reserve(String key, Cancellation cancellation) {
            if (shut || running.size() >= MAX_STREAMS || running.containsKey(key)) {
                return false;
            }
            running.put(key, cancellation);
            final Long cancelledAt = early.remove(key);
            if (cancelledAt != null && System.nanoTime() - cancelledAt < EARLY_NANOS) {
                cancellation.cancel();
            }
            return true;
        }

        void release(String key, Cancellation cancellation) {
            running.remove(key, cancellation);
        }

        void cancel(String key) {
            final Cancellation cancellation;
            synchronized (this) {
                cancellation = running.get(key);
                if (cancellation == null) {
                    remember(key);
                    return;
                }
            }
            cancellation.cancel();
        }

        /** Cancels everything running and starts nothing more: the socket is closing. */
        void shut() {
            synchronized (this) {
                shut = true;
            }
            running.values().forEach(Cancellation::cancel);
        }

        private void remember(String key) {
            final long now = System.nanoTime();
            early.values().removeIf(at -> now - at >= EARLY_NANOS);
            early.remove(key);
            early.put(key, now);
            while (early.size() > MAX_STREAMS) {
                early.remove(early.keySet().iterator().next());
            }
        }
    }
}
