package org.fuin.porelo.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A streamed call over a task's socket: a {@code tools/call} with a progress token, from a client
 * that takes {@code text/event-stream}, is answered as server-sent events, each progress one event
 * and the result the last; a client that closes the connection, or cancels by id, ends the daemon's
 * call.
 */
@org.junit.jupiter.api.Timeout(30)
class HttpStreamsTest {

    private static final String TOKEN = McpHttpServerTest.TOKEN;

    @TempDir Path directory;

    private StandInDaemon daemon;
    private McpHttpServer server;
    private Thread serving;
    private Path socket;

    @AfterEach
    void stop() throws Exception {
        if (server != null) {
            server.close();
            serving.join(5_000);
        }
        if (daemon != null) {
            daemon.close();
        }
    }

    // Harm: an agent in a task following a log sees nothing until it ends, or sees only its end.
    @Test
    void eachReplyIsAnEventAsItComesAndTheResultTheLast() throws Exception {
        CountDownLatch seen = new CountDownLatch(1);
        start(
                Map.of(
                        "Tail",
                        (parameters, replies) -> {
                            replies.more(Map.of("line", "one"));
                            // The next waits until the client has the first: nothing is held back.
                            try {
                                seen.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                            }
                            replies.last(Map.of("line", "two"));
                        }));

        try (SocketChannel client = connect()) {
            send(client, call(1, "Tail", "p-1"), true);
            InputStream in = Channels.newInputStream(client);
            String head = head(in);
            Map<String, Object> first = event(in);
            seen.countDown();
            Map<String, Object> second = event(in);
            Map<String, Object> answer = event(in);

            assertThat(head)
                    .as("an event stream")
                    .startsWith("HTTP/1.1 200 ")
                    .containsIgnoringCase("Content-Type: text/event-stream");
            assertThat(first.get("method")).as("a progress").isEqualTo("notifications/progress");
            assertThat(((Map<?, ?>) first.get("params")).get("message"))
                    .as("the first reply")
                    .isEqualTo("{\"line\":\"one\"}");
            assertThat(second.get("method"))
                    .as("the last reply as progress too")
                    .isEqualTo("notifications/progress");
            assertThat(((Map<?, ?>) answer.get("result")).get("structuredContent"))
                    .as("the result, last")
                    .isEqualTo(Map.of("line", "two"));
            assertThat(in.read()).as("then the connection ends").isEqualTo(-1);
        }
    }

    // Harm: a client that takes JSON alone gets a stream it cannot read.
    @Test
    void aClientThatDoesNotTakeAnEventStreamGetsOneAnswer() throws Exception {
        start(
                Map.of(
                        "Tail",
                        (parameters, replies) -> {
                            if (replies.streaming()) {
                                replies.more(Map.of("line", "streamed"));
                            }
                            replies.last(Map.of("line", "once"));
                        }));

        try (SocketChannel client = connect()) {
            send(client, call(1, "Tail", "p-1"), false);
            InputStream in = Channels.newInputStream(client);
            String answer = new String(in.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(answer).as("JSON").containsIgnoringCase("Content-Type: application/json");
            assertThat(answer).as("one answer, without a stream").contains("\"line\":\"once\"");
        }
    }

    // Harm: an agent that stops watching leaves the daemon streaming to nobody until porelod ends.
    @Test
    void aClientThatClosesTheConnectionEndsTheDaemonsCall() throws Exception {
        CountDownLatch gone = new CountDownLatch(1);
        start(Map.of("Watch", endless(gone)));

        try (SocketChannel client = connect()) {
            send(client, call(1, "Watch", "w"), true);
            InputStream in = Channels.newInputStream(client);
            head(in);
            event(in);
        }

        assertThat(gone.await(10, TimeUnit.SECONDS)).as("the daemon's call ended").isTrue();
    }

    // Harm: a cancel sent as MCP's notification leaving the stream it names running.
    @Test
    void aCancelOnAnotherConnectionEndsTheStreamItNamesWithoutAnAnswer() throws Exception {
        CountDownLatch gone = new CountDownLatch(1);
        start(Map.of("Watch", endless(gone)));

        try (SocketChannel client = connect()) {
            send(client, call(7, "Watch", "w"), true);
            InputStream in = Channels.newInputStream(client);
            head(in);
            event(in);
            String cancelled;
            try (SocketChannel other = connect()) {
                send(
                        other,
                        "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\","
                                + "\"params\":{\"requestId\":7}}",
                        false);
                cancelled =
                        new String(
                                Channels.newInputStream(other).readAllBytes(),
                                StandardCharsets.UTF_8);
            }
            String rest = new String(in.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(cancelled).as("the cancel taken").startsWith("HTTP/1.1 202 ");
            assertThat(gone.await(10, TimeUnit.SECONDS)).as("the daemon's call ended").isTrue();
            assertThat(rest).as("nothing after the cancel, no result and no error").isEmpty();
        }
    }

    // Harm: a cancel before the first reply leaving the client an answer without a status line.
    @Test
    void aStreamCancelledBeforeItsFirstReplyHasAnsweredTwoHundredAlready() throws Exception {
        CountDownLatch gone = new CountDownLatch(1);
        start(
                Map.of(
                        "Watch",
                        (parameters, replies) -> {
                            while (replies.open()) {
                                try {
                                    Thread.sleep(20);
                                } catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                            }
                            gone.countDown();
                        }));

        try (SocketChannel client = connect()) {
            send(client, call(3, "Watch", "w"), true);
            InputStream in = Channels.newInputStream(client);
            String head = head(in);
            try (SocketChannel other = connect()) {
                send(
                        other,
                        "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\","
                                + "\"params\":{\"requestId\":3}}",
                        false);
                Channels.newInputStream(other).readAllBytes();
            }

            assertThat(head).as("answered at once").startsWith("HTTP/1.1 200 ");
            assertThat(gone.await(10, TimeUnit.SECONDS)).as("the daemon's call ended").isTrue();
            assertThat(in.readAllBytes()).as("and nothing after it").isEmpty();
        }
    }

    // Harm: two clients of one socket streaming under one id, one of them out of a cancel's reach.
    @Test
    void aSecondStreamUnderAnIdStreamingOnAnotherConnectionIsRefused() throws Exception {
        start(Map.of("Watch", endless(new CountDownLatch(1))));

        try (SocketChannel first = connect();
                SocketChannel second = connect()) {
            send(first, call(9, "Watch", "a"), true);
            InputStream in = Channels.newInputStream(first);
            head(in);
            event(in);
            send(second, call(9, "Watch", "b"), true);
            String refused =
                    new String(
                            Channels.newInputStream(second).readAllBytes(), StandardCharsets.UTF_8);

            assertThat(refused).as("one answer, refused").contains("\"code\":-32600");
        }
    }

    // Harm: a stream whose client stopped reading holding its connection's place for good, even
    // after a cancel.
    @Test
    void aCancelLetsGoOfAStreamWhoseClientStoppedReading() throws Exception {
        String line = "x".repeat(64 * 1024);
        start(
                Map.of(
                        "Tail",
                        (parameters, replies) -> {
                            while (replies.open()) {
                                replies.more(Map.of("line", line));
                            }
                        }));

        // The accept loop holds a place while it waits; what is free when idle is the measure.
        Thread.sleep(300);
        int idle = server.freePlaces();
        try (SocketChannel client = connect()) {
            send(client, call(4, "Tail", "t"), true);
            // Not read: the server's writes fill the socket and block.
            Thread.sleep(1_000);
            try (SocketChannel other = connect()) {
                send(
                        other,
                        "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\","
                                + "\"params\":{\"requestId\":4}}",
                        false);
                Channels.newInputStream(other).readAllBytes();
            }
            boolean freed = false;
            for (int i = 0; i < 100 && !freed; i++) {
                freed = server.freePlaces() == idle;
                Thread.sleep(50);
            }

            assertThat(freed).as("every place free again, the client still not reading").isTrue();
        }
    }

    // Harm: a client that refuses an event stream given one, or one that takes it refused.
    @Test
    void acceptTakesAnEventStreamByItsMediaRangeAlone() {
        assertThat(McpHttpServer.takesEvents("application/json, text/event-stream")).isTrue();
        assertThat(McpHttpServer.takesEvents("Text/Event-Stream")).as("any case").isTrue();
        assertThat(McpHttpServer.takesEvents("text/event-stream;q=0.5")).isTrue();
        assertThat(McpHttpServer.takesEvents("text/event-stream; q=0")).as("refused").isFalse();
        assertThat(McpHttpServer.takesEvents("text/event-stream;q=0.000")).as("refused").isFalse();
        assertThat(McpHttpServer.takesEvents("text/event-stream-v2")).as("another type").isFalse();
        assertThat(McpHttpServer.takesEvents("application/json;profile=\"text/event-stream\""))
                .as("a parameter")
                .isFalse();
        assertThat(McpHttpServer.takesEvents(null)).isFalse();
    }

    // Harm: every stream cut off after the connection's deadline, which bounds only the request.
    @Test
    void theConnectionsDeadlineDoesNotCutAStream() throws Exception {
        start(
                Map.of(
                        "Tail",
                        (parameters, replies) -> {
                            replies.more(Map.of("line", "now"));
                            try {
                                Thread.sleep(1_500);
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                            }
                            replies.last(Map.of("line", "later"));
                        }));

        try (SocketChannel client = connect()) {
            send(client, call(1, "Tail", "p"), true);
            InputStream in = Channels.newInputStream(client);
            head(in);
            List<Map<String, Object>> events = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                events.add(event(in));
            }

            assertThat(((Map<?, ?>) events.getLast().get("result")).get("structuredContent"))
                    .as("the result after the 800 ms deadline")
                    .isEqualTo(Map.of("line", "later"));
        }
    }

    private void start(Map<String, VarlinkServer.Method> methods) throws Exception {
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        Map<String, VarlinkServer.Method> all = new java.util.HashMap<>(methods);
        Map<String, Object> sets =
                StdioCommandTest.sets(
                        List.of("List", "Tail", "Watch"), List.of(), List.of(), List.of());
        all.put("McpMethods", (parameters, replies) -> replies.last(sets));
        daemon =
                new StandInDaemon(
                        directory, StandInDaemon.tasks1() + StdioCommandTest.MCP_METHODS, all);
        socket = directory.resolve("task.sock");
        McpSession session =
                new McpSession(
                        Tool.of(VarlinkIdl.parse(StandInDaemon.tasks1())),
                        new SocketDaemon(daemon.socket()),
                        "0");
        server =
                new McpHttpServer(
                        socket,
                        BearerToken.read(
                                new ByteArrayInputStream(
                                        (TOKEN + "\n").getBytes(StandardCharsets.US_ASCII))),
                        session,
                        client -> true,
                        Duration.ofMillis(800));
        serving =
                Thread.ofPlatform()
                        .daemon()
                        .start(
                                () -> {
                                    try {
                                        server.serve();
                                    } catch (IOException ex) {
                                        throw new IllegalStateException(ex);
                                    }
                                });
    }

    private static VarlinkServer.Method endless(CountDownLatch gone) {
        return (parameters, replies) -> {
            replies.more(Map.of("tasks", List.of()));
            while (replies.open()) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            gone.countDown();
        };
    }

    private SocketChannel connect() throws IOException {
        SocketChannel client = SocketChannel.open(StandardProtocolFamily.UNIX);
        client.connect(UnixDomainSocketAddress.of(socket));
        return client;
    }

    private static String call(int id, String tool, String token) {
        return "{\"jsonrpc\":\"2.0\",\"id\":"
                + id
                + ",\"method\":\"tools/call\",\"params\":{\"name\":\""
                + tool
                + "\",\"arguments\":{\"task\":\"t-1\"},\"_meta\":{\"progressToken\":\""
                + token
                + "\"}}}";
    }

    private static void send(SocketChannel client, String json, boolean takesEvents)
            throws IOException {
        String request =
                "POST /mcp HTTP/1.1\r\nHost: porelo\r\nAuthorization: Bearer "
                        + TOKEN
                        + "\r\nContent-Type: application/json\r\nAccept: application/json"
                        + (takesEvents ? ", text/event-stream" : "")
                        + "\r\nContent-Length: "
                        + json.getBytes(StandardCharsets.UTF_8).length
                        + "\r\n\r\n"
                        + json;
        client.write(ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8)));
    }

    /** Reads the answer's head, to its empty line. */
    private static String head(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int b = in.read();
            assertThat(b).as("a head before the end: %s", head).isNotEqualTo(-1);
            head.append((char) b);
        }
        return head.toString();
    }

    /** Reads one event, {@code data: <json>} and an empty line, as its message. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> event(InputStream in) throws IOException {
        StringBuilder event = new StringBuilder();
        while (!event.toString().endsWith("\n\n")) {
            int b = in.read();
            assertThat(b).as("an event before the end: %s", event).isNotEqualTo(-1);
            event.append((char) b);
        }
        String text = event.toString().strip();
        assertThat(text).as("one data line").startsWith("data: ");
        return (Map<String, Object>)
                Json.parseDecimals(
                        new String(
                                text.substring(6).getBytes(StandardCharsets.ISO_8859_1),
                                StandardCharsets.UTF_8));
    }
}
