package org.fuin.porelo.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MCP over HTTP on the task's socket answers only the person's own user holding the token, within
 * fixed bounds.
 */
class McpHttpServerTest {

    static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    static final String LIST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";

    @TempDir Path directory;

    private Path socket;
    private McpHttpServer server;
    private Thread serving;
    private final AtomicBoolean peerIsOwnUser = new AtomicBoolean(true);

    @BeforeEach
    void start() throws Exception {
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        socket = directory.resolve("task.sock");
        BearerToken token =
                BearerToken.read(
                        new ByteArrayInputStream(
                                (TOKEN + "\n").getBytes(StandardCharsets.US_ASCII)));
        McpSession session =
                new McpSession(
                        Tool.of(VarlinkIdl.parse(StandInDaemon.tasks1())),
                        (method, parameters) -> Map.of(),
                        "0");
        server =
                new McpHttpServer(
                        socket,
                        token,
                        session,
                        client -> peerIsOwnUser.get(),
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

    @AfterEach
    void stop() throws Exception {
        server.close();
        serving.join(5_000);
    }

    // Harm: a tool called by a process that does not hold the token.
    @Test
    void aRequestWithoutTheRightTokenGetsUnauthorizedAndTheRightOneListsTheTools()
            throws Exception {
        assertThat(status(exchange(post(LIST, null)))).as("no token").isEqualTo(401);
        assertThat(status(exchange(post(LIST, "Bearer wrong")))).as("a wrong token").isEqualTo(401);
        assertThat(status(exchange(post(LIST, "Bearer ")))).as("an empty bearer").isEqualTo(401);
        assertThat(status(exchange(post(LIST, "Bearer " + TOKEN + "0"))))
                .as("a token one byte longer")
                .isEqualTo(401);
        assertThat(status(exchange(post(LIST, "Basic " + TOKEN))))
                .as("the token under another scheme")
                .isEqualTo(401);

        String answer = exchange(post(LIST, "Bearer " + TOKEN));

        assertThat(status(answer)).as("the right token").isEqualTo(200);
        assertThat(answer).as("the tools of the interface").contains("\"name\":\"List\"");
    }

    // Harm: a request without the token learning which paths or methods exist.
    @Test
    void withoutTheTokenAnyPathAndMethodGetsUnauthorizedFirst() throws Exception {
        String other = "GET /other HTTP/1.1\r\nHost: x\r\n\r\n";

        assertThat(status(exchange(other)))
                .as("the token is checked before path and method")
                .isEqualTo(401);
        assertThat(
                        status(
                                exchange(
                                        "GET /mcp HTTP/1.1\r\nAuthorization: Bearer "
                                                + TOKEN
                                                + "\r\n\r\n")))
                .as("with the token, GET is not allowed")
                .isEqualTo(405);
        assertThat(
                        status(
                                exchange(
                                        "POST /other HTTP/1.1\r\nAuthorization: Bearer "
                                                + TOKEN
                                                + "\r\nContent-Length: 0\r\n\r\n")))
                .as("with the token, another path is not found")
                .isEqualTo(404);
    }

    // Harm: another user's process on the host reaching the tools, a valid token or not.
    @Test
    void aPeerOfAnotherUserGetsNoAnswerEvenWithTheRightToken() throws Exception {
        peerIsOwnUser.set(false);

        assertThat(exchange(post(LIST, "Bearer " + TOKEN))).as("closed without a byte").isEmpty();
    }

    // Harm: a second request smuggled behind the first on one connection, framed otherwise than a
    // gateway framed it.
    @Test
    void aSecondRequestOnTheSameConnectionIsNeverAnswered() throws Exception {
        String twice =
                post(LIST, "Bearer " + TOKEN)
                        + post(LIST.replace("\"id\":1", "\"id\":2"), "Bearer " + TOKEN);

        String answer = exchange(twice);

        assertThat(answer)
                .as("one answer, then the connection closes")
                .contains("\"id\":1")
                .doesNotContain("\"id\":2");
        assertThat(answer.split("HTTP/1.1 ", -1)).as("one status line").hasSize(2);
        assertThat(answer).as("the server says it closes").contains("Connection: close");
    }

    // Harm: a framing two readers could take differently, which is how a request is smuggled.
    @Test
    void everyFramingAReaderCouldTakeOtherwiseIsRefused() throws Exception {
        String auth = "Authorization: Bearer " + TOKEN + "\r\n";
        Map<String, String> refused = new java.util.LinkedHashMap<>();
        refused.put(
                "Transfer-Encoding",
                "POST /mcp HTTP/1.1\r\n" + auth + "Transfer-Encoding: chunked\r\n\r\n0\r\n\r\n");
        refused.put(
                "two Content-Lengths",
                "POST /mcp HTTP/1.1\r\n"
                        + auth
                        + "Content-Length: 2\r\nContent-Length: 2\r\n\r\n{}");
        refused.put(
                "Content-Length +10",
                "POST /mcp HTTP/1.1\r\n" + auth + "Content-Length: +2\r\n\r\n{}");
        refused.put(
                "two Authorizations",
                "POST /mcp HTTP/1.1\r\n" + auth + auth + "Content-Length: 2\r\n\r\n{}");
        refused.put("a bare LF", "POST /mcp HTTP/1.1\n" + auth + "Content-Length: 2\r\n\r\n{}");
        refused.put(
                "a folded line",
                "POST /mcp HTTP/1.1\r\n" + auth + "X-A: b\r\n c\r\nContent-Length: 2\r\n\r\n{}");
        refused.put(
                "space before a colon",
                "POST /mcp HTTP/1.1\r\n" + auth + "Content-Length : 2\r\n\r\n{}");
        refused.put(
                "a short body", "POST /mcp HTTP/1.1\r\n" + auth + "Content-Length: 50\r\n\r\n{}");
        refused.put(
                "a control byte around a value",
                "POST /mcp HTTP/1.1\r\n" + auth + "Content-Length: \u000b2\r\n\r\n{}");
        refused.put(
                "a control byte inside a value",
                "POST /mcp HTTP/1.1\r\n" + auth + "X-A: a\u0001b\r\nContent-Length: 2\r\n\r\n{}");
        refused.put(
                "two Hosts",
                "POST /mcp HTTP/1.1\r\nHost: a\r\nHost: b\r\n"
                        + auth
                        + "Content-Length: 2\r\n\r\n{}");

        for (Map.Entry<String, String> each : refused.entrySet()) {
            assertThat(status(exchange(each.getValue(), true))).as(each.getKey()).isEqualTo(400);
        }
        assertThat(status(exchange("POST /mcp HTTP/1.1\r\n" + auth + "\r\n")))
                .as("no Content-Length")
                .isEqualTo(400);
    }

    // Harm: a peer making the process read or hold without bound.
    @Test
    void aHeadOrABodyBeyondItsBoundIsRefused() throws Exception {
        String auth = "Authorization: Bearer " + TOKEN + "\r\n";
        String longHead =
                "POST /mcp HTTP/1.1\r\nX-Long: "
                        + "a".repeat(HttpRequestHead.MAX_HEAD)
                        + "\r\n\r\n";
        StringBuilder manyLines = new StringBuilder("POST /mcp HTTP/1.1\r\n");
        for (int i = 0; i <= HttpRequestHead.MAX_LINES; i++) {
            manyLines.append("X-").append(i).append(": a\r\n");
        }
        manyLines.append("\r\n");
        String bigBody =
                "POST /mcp HTTP/1.1\r\n"
                        + auth
                        + "Content-Length: "
                        + (HttpRequestHead.MAX_BODY + 1)
                        + "\r\n\r\n";

        assertThat(status(exchange(longHead))).as("a head over 16 KiB").isEqualTo(431);
        assertThat(status(exchange(manyLines.toString())))
                .as("more than 64 header lines")
                .isEqualTo(431);
        assertThat(status(exchange(bigBody)))
                .as("a body over 1 MiB, refused before it is read")
                .isEqualTo(413);
    }

    // Harm: one stalled peer, without the token, holding every other connection up.
    @Test
    @org.junit.jupiter.api.Timeout(15)
    void aStalledPeerIsClosedAfterTheDeadlineWhileAnotherIsAnswered() throws Exception {
        try (SocketChannel stalled = connect()) {
            stalled.write(
                    ByteBuffer.wrap(
                            "POST /mcp HTTP/1.1\r\nX-Half".getBytes(StandardCharsets.US_ASCII)));

            assertThat(status(exchange(post(LIST, "Bearer " + TOKEN))))
                    .as("another connection meanwhile")
                    .isEqualTo(200);

            long start = System.nanoTime();
            int read = stalled.read(ByteBuffer.allocate(64));
            assertThat(read).as("the stalled connection is closed, not answered").isEqualTo(-1);
            assertThat(Duration.ofNanos(System.nanoTime() - start))
                    .as("within the deadline and a margin")
                    .isLessThan(Duration.ofSeconds(5));
        }
    }

    // Harm: a socket another user on the host could open.
    @Test
    void theSocketHasModeSixHundred() throws Exception {
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(socket)))
                .as("only the owner may open it")
                .isEqualTo("rw-------");
    }

    // Harm: a client of the newer MCP era never falling back, and so never reaching the tools.
    @Test
    void aRequestOfTheNewerEraGetsAFourHundredSoTheClientFallsBack() throws Exception {
        String modern =
                "POST /mcp HTTP/1.1\r\nAuthorization: Bearer "
                        + TOKEN
                        + "\r\nMCP-Protocol-Version: 2026-07-28\r\n"
                        + "Mcp-Method: server/discover\r\nContent-Length: 2\r\n\r\n{}";
        String current =
                "POST /mcp HTTP/1.1\r\nAuthorization: Bearer "
                        + TOKEN
                        + "\r\nMCP-Protocol-Version: 2025-11-25\r\n"
                        + "Content-Length: "
                        + LIST.length()
                        + "\r\n\r\n"
                        + LIST;

        String methodOnly =
                "POST /mcp HTTP/1.1\r\nAuthorization: Bearer "
                        + TOKEN
                        + "\r\n"
                        + "Mcp-Method: server/discover\r\nContent-Length: 2\r\n\r\n{}";

        assertThat(status(exchange(modern))).as("a newer version").isEqualTo(400);
        assertThat(status(exchange(methodOnly)))
                .as("the newer era's method header alone")
                .isEqualTo(400);
        assertThat(status(exchange(current))).as("a version it speaks is served").isEqualTo(200);
    }

    // Harm: every answered connection held until its deadline by a client that keeps its end open.
    @Test
    @org.junit.jupiter.api.Timeout(15)
    void anAnsweredConnectionIsLetGoRightAfterItsAnswerWhileTheClientStillHoldsIt()
            throws Exception {
        try (Running slow =
                        running(
                                directory.resolve("slow.sock"),
                                client -> true,
                                Duration.ofSeconds(10));
                SocketChannel client = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            client.connect(UnixDomainSocketAddress.of(slow.socket()));
            client.write(
                    ByteBuffer.wrap(
                            post(LIST, "Bearer " + TOKEN).getBytes(StandardCharsets.UTF_8)));
            var answer = new java.io.ByteArrayOutputStream();
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (client.read(buffer) >= 0) {
                answer.write(buffer.array(), 0, buffer.position());
                buffer.clear();
            }
            Thread.sleep(300);

            assertThat(status(answer.toString(StandardCharsets.UTF_8)))
                    .as("answered")
                    .isEqualTo(200);
            assertThat(slow.server().pendingDeadlines())
                    .as("the server is done with it, though the client has not closed")
                    .isZero();
        }
    }

    // Harm: deadlines of finished connections piling up in memory, one per connection made.
    @Test
    void aFinishedConnectionLeavesNoDeadlineBehind() throws Exception {
        for (int i = 0; i < 20; i++) {
            exchange(post(LIST, "Bearer " + TOKEN));
        }
        // The client's read ends with the server's shutdownOutput, just before the server cancels
        // the deadline. The wait stays well below the 800 ms deadline, so one left behind is still
        // queued and seen, not run out.
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(200);
        while (server.pendingDeadlines() > 0 && System.nanoTime() < until) {
            Thread.sleep(10);
        }

        assertThat(server.pendingDeadlines()).as("no deadline left queued").isZero();
    }

    // Harm: one peer opening connection after connection until the process has no descriptor left.
    @Test
    @org.junit.jupiter.api.Timeout(20)
    void atMostSixtyFourConnectionsAreServedAtOnceAndTheNextWaitsForAPlace() throws Exception {
        List<SocketChannel> stalled = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < McpHttpServer.MAX_CONNECTIONS; i++) {
                SocketChannel each = connect();
                each.write(
                        ByteBuffer.wrap(
                                "POST /mcp HTTP/1.1\r\nX-Half"
                                        .getBytes(StandardCharsets.US_ASCII)));
                stalled.add(each);
            }
            Thread.sleep(200);
            long start = System.nanoTime();

            String answer = exchange(post(LIST, "Bearer " + TOKEN));

            assertThat(status(answer)).as("answered once a place is free").isEqualTo(200);
            assertThat(Duration.ofNanos(System.nanoTime() - start))
                    .as("only after a stalled connection reached its 800 ms deadline")
                    .isGreaterThan(Duration.ofMillis(400));
        } finally {
            for (SocketChannel each : stalled) {
                each.close();
            }
        }
    }

    // Harm: the kernel's peer check passing another user, which no stand-in predicate would show.
    @Test
    void theKernelsPeerCheckAnswersTheOwnerAndNoOtherUser() throws Exception {
        var users = directory.getFileSystem().getUserPrincipalLookupService();
        var owner = Files.getOwner(directory);
        var root = users.lookupPrincipalByName("root");
        try (Running own =
                        running(
                                directory.resolve("own.sock"),
                                McpHttpServer.sameUserAs(owner),
                                Duration.ofSeconds(5));
                Running other =
                        running(
                                directory.resolve("other.sock"),
                                McpHttpServer.sameUserAs(root),
                                Duration.ofSeconds(5))) {
            assertThat(owner).as("the test does not run as root").isNotEqualTo(root);

            assertThat(status(exchange(own.socket(), post(LIST, "Bearer " + TOKEN), false)))
                    .as("the owner")
                    .isEqualTo(200);
            assertThat(exchange(other.socket(), post(LIST, "Bearer " + TOKEN), false))
                    .as("another user gets nothing")
                    .isEmpty();
        }
    }

    // Harm: the token printed wherever a request's head is logged or shown in an exception.
    @Test
    void aRequestsHeadNeverShowsItsHeaderValuesAsText() throws Exception {
        HttpRequestHead head =
                HttpRequestHead.read(
                        new ByteArrayInputStream(
                                ("POST /mcp HTTP/1.1\r\nAuthorization: Bearer "
                                                + TOKEN
                                                + "\r\n\r\n")
                                        .getBytes(StandardCharsets.US_ASCII)));

        assertThat(head.toString())
                .as("no token in its text")
                .doesNotContain(TOKEN)
                .doesNotContain(TOKEN.substring(0, 16));
    }

    /** A second server for one test, on a socket of its own. */
    record Running(McpHttpServer server, Thread thread, Path socket) implements AutoCloseable {
        @Override
        public void close() throws InterruptedException {
            server.close();
            thread.join(5_000);
        }
    }

    private Running running(
            Path at, java.util.function.Predicate<SocketChannel> peer, Duration deadline)
            throws Exception {
        BearerToken token =
                BearerToken.read(
                        new ByteArrayInputStream(
                                (TOKEN + "\n").getBytes(StandardCharsets.US_ASCII)));
        McpSession session =
                new McpSession(
                        Tool.of(VarlinkIdl.parse(StandInDaemon.tasks1())),
                        (method, parameters) -> Map.of(),
                        "0");
        McpHttpServer started = new McpHttpServer(at, token, session, peer, deadline);
        Thread thread =
                Thread.ofPlatform()
                        .daemon()
                        .start(
                                () -> {
                                    try {
                                        started.serve();
                                    } catch (IOException ex) {
                                        throw new IllegalStateException(ex);
                                    }
                                });
        return new Running(started, thread, at);
    }

    static String post(String json, String authorization) {
        return "POST /mcp HTTP/1.1\r\nHost: porelo\r\nContent-Type: application/json\r\n"
                + (authorization == null ? "" : "Authorization: " + authorization + "\r\n")
                + "Content-Length: "
                + json.getBytes(StandardCharsets.UTF_8).length
                + "\r\n\r\n"
                + json;
    }

    private String exchange(String request) throws IOException {
        return exchange(request, false);
    }

    /** Sends a request and reads everything until the server closes. */
    private String exchange(String request, boolean halfClose) throws IOException {
        return exchange(socket, request, halfClose);
    }

    private static String exchange(Path at, String request, boolean halfClose) throws IOException {
        try (SocketChannel client = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            client.connect(UnixDomainSocketAddress.of(at));
            client.write(ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8)));
            if (halfClose) {
                client.shutdownOutput();
            }
            var answer = new java.io.ByteArrayOutputStream();
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            try {
                while (client.read(buffer) >= 0) {
                    answer.write(buffer.array(), 0, buffer.position());
                    buffer.clear();
                }
            } catch (java.net.SocketException reset) {
                // A connection closed without an answer may arrive as a reset; what was read
                // stands.
            }
            return answer.toString(StandardCharsets.UTF_8);
        }
    }

    private SocketChannel connect() throws IOException {
        SocketChannel client = SocketChannel.open(StandardProtocolFamily.UNIX);
        client.connect(UnixDomainSocketAddress.of(socket));
        return client;
    }

    static int status(String answer) {
        assertThat(answer).as("an HTTP answer").startsWith("HTTP/1.1 ");
        return Integer.parseInt(answer.substring(9, 12));
    }
}
