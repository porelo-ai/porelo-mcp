package org.fuin.porelo.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.fuin.porelo.mcp.ContractToolsTest.request;
import static org.fuin.porelo.mcp.ContractToolsTest.result;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A call reaches the daemon as the agent made it, and the daemon's answer reaches the agent as it
 * came.
 */
class CallsTest {

    @TempDir Path directory;

    // Harm: a refusal the agent never sees, or sees in other words than the daemon's, so it acts on
    // a wrong belief.
    @Test
    void aRefusalOfTheDaemonReachesTheAgentWithItsNameAndParametersUnchanged() throws Exception {
        VarlinkServer.Method refuse =
                (parameters, replies) -> {
                    throw new VarlinkException(
                            "org.fuin.porelo.Tasks1.EarlierWorkWaits",
                            Map.of("task", "t-1", "commit", "abc123", "subject", "Fix the gate"));
                };
        try (var daemon = standIn(Map.of("Stop", refuse))) {
            McpSession session = session(daemon);

            Map<?, ?> answer =
                    (Map<?, ?>) result(session.handle(call(7, "Stop", Map.of("task", "t-1"))));

            assertThat(answer.get("isError"))
                    .as("a refusal is a tool error the agent reads")
                    .isEqualTo(true);
            assertThat(answer.get("structuredContent"))
                    .as("the daemon's error name and parameters, exactly")
                    .isEqualTo(
                            Map.of(
                                    "error",
                                    "org.fuin.porelo.Tasks1.EarlierWorkWaits",
                                    "parameters",
                                    Map.of(
                                            "task",
                                            "t-1",
                                            "commit",
                                            "abc123",
                                            "subject",
                                            "Fix the gate")));
            assertThat(Json.parseDecimals(textOf(answer)))
                    .as("the text says the same as the structured content")
                    .isEqualTo(answer.get("structuredContent"));
            assertThat(daemon.calls)
                    .as("the call reached the daemon with the agent's arguments")
                    .containsExactly(
                            Map.entry("org.fuin.porelo.Tasks1.Stop", Map.of("task", "t-1")));
        }
    }

    // Harm: porelo-mcp deciding which arguments are acceptable, a rule of its own instead of the
    // daemon's.
    @Test
    void argumentsTheSchemaWouldRefuseStillGoToTheDaemonWhichRefusesThem() throws Exception {
        try (var daemon = standIn(Map.of())) {
            McpSession session = session(daemon);

            Map<?, ?> answer =
                    (Map<?, ?>)
                            result(
                                    session.handle(
                                            call(1, "Logs", Map.of("task", 42, "unknown", true))));

            assertThat(answer.get("isError")).as("the daemon refused").isEqualTo(true);
            assertThat(((Map<?, ?>) answer.get("structuredContent")).get("error"))
                    .as("the daemon's own refusal; Logs is not registered on the stand-in")
                    .isEqualTo("org.varlink.service.MethodNotFound");
            assertThat(daemon.calls).as("the stand-in's own handlers saw nothing").isEmpty();
        }
    }

    // Harm: an agent told a call failed for its own reasons when the daemon was in fact never
    // reached.
    @Test
    void aDaemonThatCannotBeReachedIsAnInternalErrorNamingItsSocket() throws Exception {
        Path missing = directory.resolve("nobody-here.sock");
        McpSession session = new McpSession(tools(), new SocketDaemon(missing), "0");

        Map<?, ?> response =
                (Map<?, ?>) Json.parseDecimals(session.handle(call(3, "List", Map.of())));

        Map<?, ?> error = (Map<?, ?>) response.get("error");
        assertThat(error.get("code"))
                .as("JSON-RPC's internal error")
                .isEqualTo(new java.math.BigDecimal(-32603));
        assertThat((String) error.get("message"))
                .as("names the socket it tried")
                .contains(missing.toString());
        assertThat(response.get("id"))
                .as("the request's id")
                .isEqualTo(new java.math.BigDecimal(3));
    }

    // Harm: a call under a name that is no tool slipping through to the daemon as some other
    // method.
    @Test
    void aNameThatIsNoToolIsRefusedWithoutReachingTheDaemon() throws Exception {
        try (var daemon = standIn(Map.of())) {
            McpSession session = session(daemon);

            Map<?, ?> response =
                    (Map<?, ?>)
                            Json.parseDecimals(
                                    session.handle(
                                            call(4, "org.varlink.service.GetInfo", Map.of())));

            assertThat(((Map<?, ?>) response.get("error")).get("code"))
                    .as("invalid params: no such tool")
                    .isEqualTo(new java.math.BigDecimal(-32602));
            assertThat(daemon.calls).as("the daemon was not called").isEmpty();
        }
    }

    // Harm: a client matching answers to requests by id, losing its answer when 2 comes back as
    // 2.0.
    @Test
    void theIdComesBackExactlyAsItWasSent() throws Exception {
        McpSession session = new McpSession(tools(), (method, parameters) -> Map.of(), "0");

        String answer = session.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}");
        String named = session.handle("{\"jsonrpc\":\"2.0\",\"id\":\"a-2\",\"method\":\"ping\"}");

        assertThat(answer)
                .as("the number 2, not 2.0")
                .contains("\"id\":2,")
                .doesNotContain("\"id\":2.0");
        assertThat(named).as("a string id as sent").contains("\"id\":\"a-2\"");
    }

    // Harm: a notification answered, which a client reads as a response to something it never
    // asked.
    @Test
    void aNotificationGetsNoAnswer() throws Exception {
        McpSession session = new McpSession(tools(), (method, parameters) -> Map.of(), "0");

        assertThat(session.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .as("no answer")
                .isNull();
    }

    // Harm: a whole number the daemon sent reaching the agent changed, as 2.0 or 6.7108864E7.
    @Test
    void wholeNumbersOfAReplyAndARefusalReachTheAgentWrittenAsTheDaemonWroteThem()
            throws Exception {
        VarlinkServer.Method reply =
                (parameters, replies) ->
                        replies.last(Map.of("count", 2L, "limit", 67108864L, "ratio", 0.5));
        VarlinkServer.Method refuse =
                (parameters, replies) -> {
                    throw new VarlinkException(
                            "org.fuin.porelo.Tasks1.TooLarge", Map.of("limit", 67108864L));
                };
        try (var daemon = standIn(Map.of("List", reply, "Stop", refuse))) {
            McpSession session = session(daemon);

            String replied = session.handle(call(1, "List", Map.of()));
            String refused = session.handle(call(2, "Stop", Map.of("task", "t-1")));

            assertThat(replied)
                    .as("2 and 67108864 as written, a fraction kept")
                    .contains("\"count\":2")
                    .contains("\"limit\":67108864")
                    .contains("\"ratio\":0.5")
                    .doesNotContain("\"count\":2.0")
                    .doesNotContain("E7");
            assertThat(refused)
                    .as("a refusal's parameter as written")
                    .contains("\"limit\":67108864")
                    .doesNotContain("E7");
        }
    }

    // Harm: an agent told of a broken daemon connection without learning which daemon it was.
    @Test
    void aDaemonThatClosesOrAnswersGarbageIsAnInternalErrorNamingItsSocket() throws Exception {
        for (String answered : List.of("", "not json\0")) {
            Path socket = directory.resolve("raw-" + answered.length() + ".sock");
            try (var raw =
                    java.nio.channels.ServerSocketChannel.open(
                            java.net.StandardProtocolFamily.UNIX)) {
                raw.bind(java.net.UnixDomainSocketAddress.of(socket));
                Thread answering =
                        Thread.ofPlatform().daemon().start(() -> answerOnce(raw, answered));
                McpSession session = new McpSession(tools(), new SocketDaemon(socket), "0");

                Map<?, ?> response =
                        (Map<?, ?>) Json.parseDecimals(session.handle(call(5, "List", Map.of())));
                answering.join(5_000);

                Map<?, ?> error = (Map<?, ?>) response.get("error");
                assertThat(error)
                        .as("an error, not a crash, for %d bytes", answered.length())
                        .isNotNull();
                assertThat(error.get("code"))
                        .as("JSON-RPC's internal error")
                        .isEqualTo(new java.math.BigDecimal(-32603));
                assertThat((String) error.get("message"))
                        .as("names the socket")
                        .contains(socket.toString());
            }
        }
    }

    // Harm: a client of both MCP eras finding no version to fall back to, and so no tools.
    @Test
    void initializeAnswersTheAskedVersionItSpeaksAndItsNewestOtherwise() throws Exception {
        McpSession session = new McpSession(tools(), (method, parameters) -> Map.of(), "1.2.3");

        Map<?, ?> asked =
                (Map<?, ?>)
                        result(
                                session.handle(
                                        request(
                                                1,
                                                "initialize",
                                                Map.of("protocolVersion", "2025-06-18"))));
        Map<?, ?> newer =
                (Map<?, ?>)
                        result(
                                session.handle(
                                        request(
                                                2,
                                                "initialize",
                                                Map.of("protocolVersion", "2026-07-28"))));
        String discover = session.handle(request(3, "server/discover", Map.of()));

        assertThat(asked.get("protocolVersion"))
                .as("a version it speaks, as asked")
                .isEqualTo("2025-06-18");
        assertThat(newer.get("protocolVersion"))
                .as("a version it does not speak gets its newest")
                .isEqualTo("2025-11-25");
        assertThat(asked.get("serverInfo"))
                .as("its name and version")
                .isEqualTo(Map.of("name", "porelo-mcp", "version", "1.2.3"));
        assertThat(discover).as("the newer era's discovery is no method here").contains("-32601");
    }

    // Harm: an answer a client cannot match, or a message of another protocol taken as MCP.
    @Test
    void aMessageThatIsNoJsonRpcTwoRequestIsRefusedWithANullOrItsOwnId() throws Exception {
        McpSession session = new McpSession(tools(), (method, parameters) -> Map.of(), "0");

        Map<?, ?> arrayId = answer(session, "{\"jsonrpc\":\"2.0\",\"id\":[1,2]}");
        Map<?, ?> objectId =
                answer(session, "{\"jsonrpc\":\"2.0\",\"id\":{\"a\":1},\"method\":99}");
        Map<?, ?> noVersion = answer(session, "{\"id\":4,\"method\":\"ping\"}");
        Map<?, ?> otherVersion =
                answer(session, "{\"jsonrpc\":\"1.0\",\"id\":5,\"method\":\"ping\"}");

        assertThat(arrayId.get("id")).as("an array id is not echoed").isNull();
        assertThat(objectId.get("id")).as("an object id is not echoed").isNull();
        for (Map<?, ?> each : List.of(arrayId, objectId, noVersion, otherVersion)) {
            assertThat(((Map<?, ?>) each.get("error")).get("code"))
                    .as("an invalid request: %s", each)
                    .isEqualTo(new java.math.BigDecimal(-32600));
        }
        assertThat(noVersion.get("id"))
                .as("a valid id is kept")
                .isEqualTo(new java.math.BigDecimal(4));
    }

    private static Map<?, ?> answer(McpSession session, String message) {
        return (Map<?, ?>) Json.parseDecimals(session.handle(message));
    }

    private static void answerOnce(java.nio.channels.ServerSocketChannel raw, String answered) {
        try (var client = raw.accept()) {
            client.read(java.nio.ByteBuffer.allocate(65536));
            client.write(
                    java.nio.ByteBuffer.wrap(
                            answered.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.io.IOException ex) {
            // The test reads what the session made of it.
        }
    }

    private StandInDaemon standIn(Map<String, VarlinkServer.Method> methods) throws Exception {
        return new StandInDaemon(directory, StandInDaemon.tasks1(), methods);
    }

    private static McpSession session(StandInDaemon daemon) throws Exception {
        return new McpSession(tools(), new SocketDaemon(daemon.socket()), "0");
    }

    private static List<Tool> tools() throws Exception {
        return Tool.of(VarlinkIdl.parse(StandInDaemon.tasks1()));
    }

    private static String call(Object id, String tool, Map<String, Object> arguments) {
        return request(id, "tools/call", Map.of("name", tool, "arguments", arguments));
    }

    private static String textOf(Map<?, ?> answer) {
        return (String) ((Map<?, ?>) ((List<?>) answer.get("content")).getFirst()).get("text");
    }
}
