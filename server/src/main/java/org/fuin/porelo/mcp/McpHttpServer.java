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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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

    private final Path socket;
    private final BearerToken token;
    private final McpSession session;
    private final Predicate<SocketChannel> peer;
    private final Duration deadline;
    private final ServerSocketChannel channel;
    private final ScheduledExecutorService closer;
    private volatile boolean closed;

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
                Executors.newSingleThreadScheduledExecutor(
                        task -> Thread.ofPlatform().daemon().name("mcp-deadline").unstarted(task));
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
            final SocketChannel client;
            try {
                client = channel.accept();
            } catch (IOException ex) {
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
            if (!peer.test(client)) {
                // Another user's process gets no answer at all, not even a refusal to learn from.
                return;
            }
            final var timeout =
                    closer.schedule(
                            () -> closeQuietly(client), deadline.toMillis(), TimeUnit.MILLISECONDS);
            try {
                final InputStream in = Channels.newInputStream(client);
                answer(in, Channels.newOutputStream(client));
                // Closing with unread bytes resets the connection and drops the answer just sent,
                // so the
                // rest is read away first: bounded, and within the same deadline.
                client.shutdownOutput();
                drain(in);
            } finally {
                timeout.cancel(false);
            }
        } catch (IOException | RuntimeException ex) {
            // A connection that broke or ran out of time is simply closed.
        }
    }

    private void answer(InputStream in, OutputStream out) throws IOException {
        final HttpRequestHead head;
        try {
            head = HttpRequestHead.read(in);
        } catch (HttpRequestHead.Refused refused) {
            respond(out, refused.status(), null);
            return;
        }
        if (!token.accepts(head.header("authorization"))) {
            respond(out, 401, null);
            return;
        }
        if (!head.target().equals(PATH)) {
            respond(out, 404, null);
            return;
        }
        if (!head.method().equals("POST")) {
            respond(out, 405, null);
            return;
        }
        final String version = head.header("mcp-protocol-version");
        if (head.header("mcp-method") != null
                || version != null && !McpSession.VERSIONS.contains(version)) {
            // A client of both eras probes in the newer one first and falls back on a 4xx like
            // this.
            respond(
                    out,
                    400,
                    jsonRpcError(
                            "This server speaks MCP " + String.join(", ", McpSession.VERSIONS)));
            return;
        }
        final byte[] body;
        try {
            body = head.body(in);
        } catch (HttpRequestHead.Refused refused) {
            respond(out, refused.status(), null);
            return;
        }
        final String answered = session.handle(new String(body, StandardCharsets.UTF_8));
        if (answered == null) {
            respond(out, 202, null);
        } else {
            respond(out, 200, answered);
        }
    }

    private static void drain(InputStream in) throws IOException {
        final byte[] discard = new byte[8192];
        long left = HttpRequestHead.MAX_HEAD + (long) HttpRequestHead.MAX_BODY;
        int read;
        while (left > 0
                && (read = in.read(discard, 0, (int) Math.min(discard.length, left))) >= 0) {
            left -= read;
        }
    }

    private static String jsonRpcError(String message) {
        final Map<String, Object> error = new java.util.LinkedHashMap<>();
        error.put("jsonrpc", "2.0");
        error.put("id", null);
        error.put("error", Map.of("code", McpSession.INVALID_REQUEST, "message", message));
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

    /** Stops accepting and removes the socket's path, which this server bound itself. */
    @Override
    public void close() {
        closed = true;
        try {
            channel.close();
        } catch (IOException ex) {
            // Closing is all that is left to do.
        }
        try {
            Files.deleteIfExists(socket);
        } catch (IOException ex) {
            // The path is left; the daemon removes a stale socket before the next start.
        }
        closer.shutdownNow();
    }
}
