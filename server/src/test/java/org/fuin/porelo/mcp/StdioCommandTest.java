package org.fuin.porelo.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.fuin.porelo.mcp.ContractToolsTest.request;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code porelo-mcp stdio}: a person's own client gets only what the daemon's sets allow, and only
 * protocol on standard output.
 */
class StdioCommandTest {

    static final String MCP_METHODS =
            "\n# The sets for a person's own client.\n"
                    + "method McpMethods() -> (reading: []string, writing: []string, never: []string, judgement: []string)\n";

    @TempDir Path directory;

    // Harm: a destructive method reaching an agent because the daemon's writing set named it by
    // fault.
    @Test
    void aMethodInNeverIsNoToolAndNeverReachesTheDaemonWhateverWritingSays() throws Exception {
        try (var daemon =
                standIn(
                        sets(
                                List.of("List"),
                                List.of("Stop", "Panic"),
                                List.of("Panic"),
                                List.of()))) {
            Ran ran =
                    run(
                            daemon,
                            request(1, "tools/list", Map.of()),
                            request(
                                    2,
                                    "tools/call",
                                    Map.of("name", "Panic", "arguments", Map.of())));

            assertThat(ran.code).as("ends with its input").isEqualTo(0);
            assertThat(toolNames(ran.answers.get(0)))
                    .as("reading and writing, never Panic")
                    .containsExactly("List", "Stop");
            assertThat(((Map<?, ?>) ran.answers.get(1).get("error")).get("code"))
                    .as("Panic is no tool")
                    .isEqualTo(new java.math.BigDecimal(-32602));
            assertThat(daemon.calls.stream().map(Map.Entry::getKey))
                    .as("the daemon saw the sets asked, and no Panic")
                    .containsExactly(McpMethods.METHOD);
        }
    }

    // Harm: a client whose protocol stream is corrupted by a log line or a stack trace.
    @Test
    void anMcpClientOverPipesListsAndCallsAndSeesNothingButJsonRpcOnStandardOutput()
            throws Exception {
        VarlinkServer.Method list =
                (parameters, replies) ->
                        replies.last(Map.of("tasks", List.of(), "unrecognized", List.of()));
        try (var daemon =
                standIn(
                        sets(List.of("List"), List.of("Stop"), List.of(), List.of()),
                        Map.of("List", list))) {
            Ran ran =
                    run(
                            daemon,
                            request(
                                    1,
                                    "initialize",
                                    Map.of(
                                            "protocolVersion",
                                            "2025-11-25",
                                            "capabilities",
                                            Map.of(),
                                            "clientInfo",
                                            Map.of("name", "test", "version", "0"))),
                            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                            request(2, "tools/call", Map.of("name", "List", "arguments", Map.of())),
                            request(
                                    3,
                                    "tools/call",
                                    Map.of("name", "Stop", "arguments", Map.of("task", "t-1"))),
                            "not json at all",
                            request(
                                    4,
                                    "tools/call",
                                    Map.of("name", "Nothing", "arguments", Map.of())));

            assertThat(ran.lines)
                    .as("one answer per request, the notification unanswered: %s", ran.err)
                    .hasSize(5);
            assertThat(ran.answers)
                    .as("every line a JSON-RPC message")
                    .allSatisfy(
                            each ->
                                    assertThat(each.get("jsonrpc"))
                                            .as("JSON-RPC 2.0")
                                            .isEqualTo("2.0"));
            assertThat(((Map<?, ?>) ran.answers.get(1).get("result")).get("structuredContent"))
                    .as("the daemon's reply")
                    .isEqualTo(Map.of("tasks", List.of(), "unrecognized", List.of()));
            assertThat(((Map<?, ?>) ran.answers.get(2).get("result")).get("isError"))
                    .as("a refused call, still protocol")
                    .isEqualTo(true);
        }
    }

    // Harm: a client that offers only MCP 2026-07-28 never reaching the tools from the person's
    // own MCP client.
    @Test
    void aClientOfTwoThousandTwentySixDiscoversAndCallsWithoutAHandshake() throws Exception {
        VarlinkServer.Method list =
                (parameters, replies) ->
                        replies.last(Map.of("tasks", List.of(), "unrecognized", List.of()));
        Map<String, Object> meta =
                Map.of(
                        McpSession.PROTOCOL_VERSION,
                        "2026-07-28",
                        McpSession.CLIENT_CAPABILITIES,
                        Map.of());
        try (var daemon =
                standIn(
                        sets(List.of("List"), List.of(), List.of(), List.of()),
                        Map.of("List", list))) {
            Ran ran =
                    run(
                            daemon,
                            request(1, "server/discover", Map.of("_meta", meta)),
                            request(
                                    2,
                                    "tools/call",
                                    Map.of("name", "List", "arguments", Map.of(), "_meta", meta)));

            assertThat(ran.code).as("ends with its input: %s", ran.err).isEqualTo(0);
            Map<?, ?> discovered = (Map<?, ?>) ran.answers.get(0).get("result");
            assertThat(discovered).as("discovered: %s", ran.answers.get(0)).isNotNull();
            assertThat(discovered.get("supportedVersions").toString())
                    .as("the version it asked for")
                    .contains("2026-07-28");
            Map<?, ?> called = (Map<?, ?>) ran.answers.get(1).get("result");
            assertThat(called.get("resultType")).as("a complete result").isEqualTo("complete");
            assertThat(called.get("structuredContent"))
                    .as("the daemon's reply")
                    .isEqualTo(Map.of("tasks", List.of(), "unrecognized", List.of()));
        }
    }

    // Harm: a writing method offered that the person never named.
    @Test
    void withNoListExactlyTheReadingSetIsOffered() throws Exception {
        try (var daemon =
                standIn(sets(List.of("List", "Logs"), List.of(), List.of("Panic"), List.of()))) {
            Ran ran = run(daemon, request(1, "tools/list", Map.of()));

            assertThat(toolNames(ran.answers.get(0)))
                    .as("the reading set, in the interface's order")
                    .containsExactlyInAnyOrder("List", "Logs");
        }
    }

    // Harm: a person unaware that their client may act in their name.
    @Test
    void aJudgementMethodOfferedIsNamedAtStartAndNoneOfferedNamesNone() throws Exception {
        try (var daemon =
                standIn(
                        sets(
                                List.of("List"),
                                List.of("Approve"),
                                List.of(),
                                List.of("Approve", "Clear")))) {
            Ran ran = run(daemon, request(1, "tools/list", Map.of()));

            assertThat(toolNames(ran.answers.get(0))).as("Approve offered").contains("Approve");
            assertThat(ran.err).as("Approve named").contains("Approve").contains("judgement");
            assertThat(ran.err).as("Clear is not offered, so not named").doesNotContain("Clear");
        }
        try (var daemon =
                standIn(sets(List.of("List"), List.of(), List.of(), List.of("Approve")))) {
            Ran ran = run(daemon, request(1, "tools/list", Map.of()));

            assertThat(ran.err)
                    .as("no judgement offered, no such line")
                    .doesNotContain("judgement");
        }
    }

    // Harm: a daemon of another version offering every tool because it cannot name its sets.
    @Test
    void aDaemonWithoutMcpMethodsOffersNoToolsAndSaysWhy() throws Exception {
        try (var daemon = new StandInDaemon(directory, StandInDaemon.tasks1(), Map.of())) {
            Ran ran = run(daemon, request(1, "tools/list", Map.of()));

            assertThat(toolNames(ran.answers.get(0))).as("no tools").isEmpty();
            assertThat(ran.err)
                    .as("says why")
                    .contains("no tools offered")
                    .contains("MethodNotFound");
        }
    }

    // Harm: one overlong message holding memory without bound, or ending the session for good.
    @Test
    void aMessageOverItsBoundIsRefusedAndTheNextOneIsAnswered() throws Exception {
        try (var daemon = standIn(sets(List.of("List"), List.of(), List.of(), List.of()))) {
            String tooLong =
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"x\":\""
                            + "a".repeat(StdioLines.MAX_LINE)
                            + "\"}";

            Ran ran = run(daemon, tooLong, request(2, "ping", Map.of()));

            assertThat(ran.answers).as("two answers: %s", ran.err).hasSize(2);
            assertThat(((Map<?, ?>) ran.answers.get(0).get("error")).get("code"))
                    .as("the overlong one refused")
                    .isEqualTo(new java.math.BigDecimal(-32600));
            assertThat(ran.answers.get(1).get("result"))
                    .as("the next one answered")
                    .isEqualTo(Map.of());
        }
    }

    // Harm: a daemon answering broken sets and every tool being offered for it.
    @Test
    void aSetThatIsMissingOrHoldsSomethingButNamesOffersNoTools() throws Exception {
        List<Map<String, Object>> broken =
                List.of(
                        Map.of("reading", List.of("List")),
                        Map.of(
                                "reading",
                                List.of("List", 42),
                                "writing",
                                List.of(),
                                "never",
                                List.of(),
                                "judgement",
                                List.of()));
        for (Map<String, Object> each : broken) {
            Path place = java.nio.file.Files.createTempDirectory(directory, "broken");
            Map<String, VarlinkServer.Method> methods =
                    Map.of("McpMethods", (parameters, replies) -> replies.last(each));
            try (var daemon =
                    new StandInDaemon(place, StandInDaemon.tasks1() + MCP_METHODS, methods)) {
                Ran ran = run(daemon, request(1, "tools/list", Map.of()));

                assertThat(toolNames(ran.answers.get(0))).as("no tools for %s", each).isEmpty();
                assertThat(ran.err).as("says why").contains("no tools offered");
            }
        }
    }

    static Map<String, Object> sets(
            List<String> reading,
            List<String> writing,
            List<String> never,
            List<String> judgement) {
        return Map.of(
                "reading", reading, "writing", writing, "never", never, "judgement", judgement);
    }

    private StandInDaemon standIn(Map<String, Object> sets) throws IOException {
        return standIn(sets, Map.of());
    }

    private StandInDaemon standIn(Map<String, Object> sets, Map<String, VarlinkServer.Method> more)
            throws IOException {
        Map<String, VarlinkServer.Method> methods = new java.util.HashMap<>(more);
        methods.put("McpMethods", (parameters, replies) -> replies.last(sets));
        return new StandInDaemon(directory, StandInDaemon.tasks1() + MCP_METHODS, methods);
    }

    @SuppressWarnings("unchecked")
    static List<String> toolNames(Map<String, Object> answer) {
        List<Object> tools =
                (List<Object>) ((Map<String, Object>) answer.get("result")).get("tools");
        return tools.stream()
                .map(tool -> (String) ((Map<String, Object>) tool).get("name"))
                .toList();
    }

    record Ran(int code, List<String> lines, List<Map<String, Object>> answers, String err) {}

    /**
     * Runs the command over pipes, sends each message as one line, closes its input, and reads all
     * it wrote.
     */
    @SuppressWarnings("unchecked")
    private static Ran run(StandInDaemon daemon, String... messages) throws Exception {
        String java = ProcessHandle.current().info().command().orElseThrow();
        ProcessBuilder builder =
                new ProcessBuilder(
                        java,
                        "-cp",
                        System.getProperty("java.class.path"),
                        Main.class.getName(),
                        "stdio",
                        "--daemon-socket",
                        daemon.socket().toString());
        Process process = builder.start();
        var err = new StringBuilder();
        Thread errors =
                Thread.ofPlatform()
                        .daemon()
                        .start(
                                () -> {
                                    try {
                                        err.append(
                                                new String(
                                                        process.getErrorStream().readAllBytes(),
                                                        StandardCharsets.UTF_8));
                                    } catch (IOException ex) {
                                        // The process ended.
                                    }
                                });
        try (OutputStream in = process.getOutputStream()) {
            for (String message : messages) {
                in.write((message + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(20, TimeUnit.SECONDS)).as("it ends with its input").isTrue();
        errors.join(5_000);
        List<String> lines =
                out.isEmpty()
                        ? List.of()
                        : List.of(out.split("\n", -1)).subList(0, out.split("\n", -1).length - 1);
        assertThat(out)
                .as("every answer ends its line")
                .satisfiesAnyOf(
                        text -> assertThat(text).as("nothing").isEmpty(),
                        text -> assertThat(text).as("a whole line").endsWith("\n"));
        List<Map<String, Object>> answers = new ArrayList<>();
        for (String line : lines) {
            answers.add((Map<String, Object>) Json.parseDecimals(line));
        }
        return new Ran(process.exitValue(), lines, answers, err.toString());
    }
}
