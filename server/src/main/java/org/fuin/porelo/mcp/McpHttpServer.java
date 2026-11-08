package org.fuin.porelo.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * MCP streamable HTTP on a Unix socket, for one task: one request per connection, each connection
 * on its own thread with a deadline, the peer's user and the bearer token checked before anything
 * else.
 */
public final class McpHttpServer implements AutoCloseable {

    /** The one path MCP is served on. */
    static final String PATH = "/mcp";

    /** The most connections served at once; the next one waits in the kernel's backlog. */
    static final int MAX_CONNECTIONS = 64;

    /** The most bytes read away after an answer, of what had already arrived. */
    static final int DISCARDED = 64 * 1024;

    private final Path socket;
    private final BearerToken token;
    private final McpSession session;
    private final Predicate<SocketChannel> peer;
    private final Duration deadline;
    private final ServerSocketChannel channel;
    private final java.util.concurrent.ScheduledThreadPoolExecutor closer;
    private final java.util.concurrent.Semaphore places =
            new java.util.concurrent.Semaphore(MAX_CONNECTIONS);
    private volatile boolean closed;

    /**
     * The streams of every connection, so the id rule and the bound hold for the socket, and a
     * cancel on one connection finds a stream on another.
     */
    private final Streams.Registry streaming = new Streams.Registry();

    /**
     * Binds the socket with mode {@code 0600}.
     *
     * @param socket Where; its place was checked before.
     * @param token What a request must carry.
     * @param session What answers the messages.
     * @param peer Whether a connected peer is the person's own user.
     * @param deadline How long one connection may take, head and body.
     * @throws IOException When the socket cannot be bound or its mode set; nothing is left bound.
     */
    public McpHttpServer(
            Path socket,
            BearerToken token,
            McpSession session,
            Predicate<SocketChannel> peer,
            Duration deadline)
            throws IOException {
        this.socket = socket;
        this.token = token;
        this.session = session;
        this.peer = peer;
        this.deadline = deadline;
        this.closer =
                new java.util.concurrent.ScheduledThreadPoolExecutor(
                        1,
                        task -> Thread.ofPlatform().daemon().name("mcp-deadline").unstarted(task));
        // A finished connection's deadline leaves the queue at once, not when it would have run.
        closer.setRemoveOnCancelPolicy(true);
        this.channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            channel.bind(UnixDomainSocketAddress.of(socket));
        } catch (IOException ex) {
            // The path was not bound, so it is not this server's to remove.
            channel.close();
            closer.shutdownNow();
            throw ex;
        }
        try {
            Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-------"));
        } catch (IOException ex) {
            close();
            throw ex;
        }
    }

    /**
     * Whether a peer is the user who owns the socket's directory, by the kernel's credentials.
     *
     * @param owner The directory's owner, checked to be this process's own user.
     * @return The check.
     */
    public static Predicate<SocketChannel> sameUserAs(UserPrincipal owner) {
        return client -> {
            try {
                return client.getOption(jdk.net.ExtendedSocketOptions.SO_PEERCRED)
                        .user()
                        .equals(owner);
            } catch (IOException | RuntimeException ex) {
                return false;
            }
        };
    }

    /**
     * Accepts connections until closed, each served on a thread of its own.
     *
     * @throws IOException When accepting fails while not closed.
     */
    public void serve() throws IOException {
        while (!closed) {
            try {
                // Waits for a free place, looking up now and then to see whether it was closed.
                if (!places.tryAcquire(200, TimeUnit.MILLISECONDS)) {
                    continue;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            final SocketChannel client;
            try {
                client = channel.accept();
            } catch (IOException ex) {
                places.release();
                if (closed) {
                    return;
                }
                throw ex;
            }
            Thread.ofVirtual().name("mcp-connection").start(() -> handle(client));
        }
    }

    private void handle(SocketChannel client) {
        try (client) {
            serveOne(client);
        } catch (IOException | RuntimeException ex) {
            // A connection that broke or ran out of time is simply closed.
        } finally {
            places.release();
        }
    }

    private void serveOne(SocketChannel client) throws IOException {
        if (!peer.test(client)) {
            // Another user's process gets no answer at all, not even a refusal to learn from.
            return;
        }
        final var timeout =
                closer.schedule(
                        () -> closeQuietly(client), deadline.toMillis(), TimeUnit.MILLISECONDS);
        try {
            final boolean streamed =
                    answer(
                            Channels.newInputStream(client),
                            Channels.newOutputStream(client),
                            () -> timeout.cancel(false),
                            () -> closeQuietly(client));
            client.shutdownOutput();
            if (!streamed) {
                discardArrived(client);
            }
        } finally {
            timeout.cancel(false);
        }
    }

    /**
     * Answers one request.
     *
     * @return {@code true} when it was answered as a stream, whose watcher still reads the input.
     */
    private boolean answer(
            InputStream in, OutputStream out, Runnable endDeadline, Runnable closeClient)
            throws IOException {
        final HttpRequestHead head;
        try {
            head = HttpRequestHead.read(in);
        } catch (HttpRequestHead.Refused refused) {
            respond(out, refused.status(), null);
            return false;
        }
        if (!token.accepts(head.header("authorization"))) {
            respond(out, 401, null);
            return false;
        }
        if (!head.target().equals(PATH)) {
            respond(out, 404, null);
            return false;
        }
        if (!head.method().equals("POST")) {
            respond(out, 405, null);
            return false;
        }
        final byte[] body;
        try {
            body = head.body(in);
        } catch (HttpRequestHead.Refused refused) {
            respond(out, refused.status(), null);
            return false;
        }
        final String message = new String(body, StandardCharsets.UTF_8);
        final Era era = Era.of(head, message);
        if (era.refusal() != null) {
            respond(out, 400, era.refusal());
            return false;
        }
        final EventStream events = new EventStream(out);
        final Streams streams =
                new Streams(
                        events::send,
                        streaming,
                        takesEvents(head.header("accept")),
                        // The head goes out as the stream starts, before its first reply.
                        events::head,
                        // A cancel also lets go of a write the client no longer reads.
                        closeClient);
        final String answered = session.handle(message, streams);
        if (streams.started()) {
            // The deadline bounds the request and a single answer; a stream runs until it ends or
            // is cancelled.
            endDeadline.run();
            // A client that closes its end - or half-closes it, which MCP clients do not - has
            // stopped the stream. Bytes beyond the request are read away, up to a bound.
            Thread.ofVirtual()
                    .name("mcp-stream-watch")
                    .start(
                            () -> {
                                try {
                                    final byte[] buffer = new byte[8192];
                                    int left = DISCARDED;
                                    int read;
                                    while (left > 0 && (read = in.read(buffer)) != -1) {
                                        left -= read;
                                    }
                                } catch (IOException ex) {
                                    // Closed: the same.
                                }
                                streams.cancelAll();
                            });
            try {
                streams.awaitAll();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                streams.cancelAll();
            }
            return true;
        }
        if (answered == null) {
            respond(out, 202, null);
        } else {
            respond(out, era.modern() && Era.refusesTheRequest(answered) ? 400 : 200, answered);
        }
        return false;
    }

    /**
     * Whether an {@code Accept} header takes {@code text/event-stream}: one of its media ranges is
     * that type, in any case, without {@code q=0}.
     *
     * @param accept The header, or {@code null}.
     * @return Whether a stream may be answered.
     */
    static boolean takesEvents(@Nullable String accept) {
        if (accept == null) {
            return false;
        }
        for (final String range : accept.split(",")) {
            final String[] parts = range.split(";");
            if (!parts[0].strip().equalsIgnoreCase("text/event-stream")) {
                continue;
            }
            boolean refused = false;
            for (int i = 1; i < parts.length; i++) {
                final String parameter = parts[i].strip().toLowerCase(java.util.Locale.ROOT);
                if (parameter.startsWith("q=")
                        && parameter.substring(2).strip().matches("0(\\.0{0,3})?")) {
                    refused = true;
                }
            }
            if (!refused) {
                return true;
            }
        }
        return false;
    }

    /**
     * An answer as server-sent events: its head written with the first event, each message one
     * {@code data:} line and an empty line, and the connection's end its end.
     */
    private static final class EventStream {

        private final OutputStream out;
        private boolean headWritten;

        EventStream(OutputStream out) {
            this.out = out;
        }

        /** Writes the head, once; a stream cancelled before its first reply still answers 200. */
        synchronized void head() {
            if (headWritten) {
                return;
            }
            headWritten = true;
            try {
                out.write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                                        + "Cache-Control: no-store\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (IOException ex) {
                // The client is gone; the first event's write says so to the stream.
            }
        }

        synchronized boolean send(String json) {
            head();
            try {
                // Json.write escapes every line break inside a string, so one message is one line.
                out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                return true;
            } catch (IOException ex) {
                return false;
            }
        }
    }

    /**
     * Reads away, without waiting, what the peer sent beyond the request: closing with unread bytes
     * resets the connection and can drop the answer just sent. At most {@link #DISCARDED} bytes,
     * never read for, and never from a peer that is still sending.
     */
    private static void discardArrived(SocketChannel client) throws IOException {
        client.configureBlocking(false);
        final java.nio.ByteBuffer discard = java.nio.ByteBuffer.allocate(8192);
        int left = DISCARDED;
        while (left > 0) {
            discard.clear().limit(Math.min(discard.capacity(), left));
            final int read = client.read(discard);
            if (read <= 0) {
                return;
            }
            left -= read;
        }
    }

    static String jsonRpcError(@Nullable Object id, int code, String message) {
        final Map<String, Object> error = new java.util.LinkedHashMap<>();
        error.put("jsonrpc", "2.0");
        error.put("id", id);
        error.put("error", Map.of("code", code, "message", message));
        return Json.write(error);
    }

    private static void respond(OutputStream out, int status, @Nullable String json)
            throws IOException {
        final byte[] body = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
        final StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        if (json != null) {
            head.append("Content-Type: application/json\r\n");
        }
        if (status == 405) {
            head.append("Allow: POST\r\n");
        }
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 202 -> "Accepted";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 413 -> "Content Too Large";
            case 431 -> "Request Header Fields Too Large";
            default -> "Status";
        };
    }

    private static void closeQuietly(SocketChannel client) {
        try {
            client.close();
        } catch (IOException ex) {
            // Already gone.
        }
    }

    /** How many connections could be served now, of {@link #MAX_CONNECTIONS}. */
    int freePlaces() {
        return places.availablePermits();
    }

    /** How many deadlines are queued, for connections not yet done. */
    int pendingDeadlines() {
        return closer.getQueue().size();
    }

    /** Stops accepting and removes the socket's path, which this server bound itself. */
    @Override
    public synchronized void close() {
        if (closed) {
            // Once only: a second removal could take a path another process has bound since.
            return;
        }
        closed = true;
        streaming.shut();
        // The path goes first, so serve() cannot return - and the process exit - before it is gone.
        try {
            Files.deleteIfExists(socket);
        } catch (IOException ex) {
            // The path is left; the daemon removes a stale socket before the next start.
        }
        try {
            channel.close();
        } catch (IOException ex) {
            // Closing is all that is left to do.
        }
        closer.shutdownNow();
    }
}
