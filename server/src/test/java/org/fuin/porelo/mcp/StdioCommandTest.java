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
 * {@code porelo-mcp tasks}: a person's own client gets only the methods marked for agents that the
 * daemon's never-set leaves, and only protocol on standard output.
 */
class StdioCommandTest {

    @TempDir Path directory;

    // Harm: a destructive method reaching an agent because its marker says true by fault.
    @Test
    void aMethodInNeverIsNoToolAndNeverReachesTheDaemonWhateverItsMarkerSays() throws Exception {
        String description = StandInDaemon.tasks1Meant("Stop", "Panic");
        try (var daemon = standIn(description, never(List.of("Panic")), Map.of())) {
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
                    .as("every method marked for agents but Panic")
                    .containsExactlyElementsOf(
                            StandInDaemon.meantIn(description).stream()
                                    .filter(name -> !name.equals("Panic"))
                                    .toList())
                    .contains("List", "Stop");
            assertThat(ran.err)
                    .as("the contradiction named")
                    .contains("Panic is marked for agents, yet the daemon never offers it");
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
                        StandInDaemon.tasks1Meant("Stop"),
                        never(List.of()),
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
            // A call answered once is answered when the daemon answers, not in the order asked.
            assertThat(((Map<?, ?>) answerTo(ran, 2).get("result")).get("structuredContent"))
                    .as("the daemon's reply")
                    .isEqualTo(Map.of("tasks", List.of(), "unrecognized", List.of()));
            assertThat(((Map<?, ?>) answerTo(ran, 3).get("result")).get("isError"))
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
        try (var daemon = standIn(StandInDaemon.tasks1(), never(List.of()), Map.of("List", list))) {
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

    // Harm: a method porelo never judged fit for agents offered to one.
    @Test
    void exactlyTheMethodsMarkedForAgentsAreOfferedAndAnUnmarkedOneIsNamed() throws Exception {
        String description =
                StandInDaemon.tasks1()
                        .replaceFirst("\n# x-mcp: [^\n]*\nmethod Stop\\(", "\nmethod Stop(");
        assertThat(description)
                .as("Stop's marker taken out")
                .contains("method Stop(")
                .doesNotContain("x-mcp: true\nmethod Stop(");
        try (var daemon = standIn(description, never(List.of()), Map.of())) {
            Ran ran = run(daemon, request(1, "tools/list", Map.of()));

            assertThat(toolNames(ran.answers.get(0)))
                    .as("the methods the pin marks true, in the interface's order")
                    .containsExactlyElementsOf(StandInDaemon.meantIn(description))
                    .doesNotContain("Panic", "Approve", "Stop");
            assertThat(ran.err)
                    .as("an unmarked method named")
                    .contains("Stop is no tool: it carries no marker");
            assertThat(ran.err)
                    .as("a method marked false with its reason is left out without a word")
                    .doesNotContain("Approve")
                    .doesNotContain("Panic");
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

    // Harm: a person's client hanging at its start for good on a daemon that takes porelo-mcp's own
    // question at start but never answers it.
    @Test
    void aStartUpQuestionTheDaemonNeverAnswersEndsTheStartAfterItsBound() throws Exception {
        VarlinkServer.Method silent =
                (parameters, replies) -> {
                    while (replies.open()) {
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                };
        try (var daemon =
                new StandInDaemon(
                        directory, StandInDaemon.tasks1(), Map.of("McpMethods", silent))) {
            String java = ProcessHandle.current().info().command().orElseThrow();
            Process process =
                    new ProcessBuilder(
                                    java,
                                    "-cp",
                                    System.getProperty("java.class.path"),
                                    Main.class.getName(),
                                    "tasks",
                                    "--daemon-socket",
                                    daemon.socket().toString())
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .start();
            try {
                // Ended by the 30 s bound; a start that hangs is ended here, so it fails, not
                // hangs.
                boolean ended = process.waitFor(60, TimeUnit.SECONDS);
                assertThat(ended).as("the start ended within 60 s").isTrue();
                String err =
                        new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

                assertThat(process.exitValue())
                        .as("the daemon was not reached: %s", err)
                        .isEqualTo(Main.NO_DAEMON);
                assertThat(err)
                        .as("says which question went unanswered")
                        .contains("was not reached")
                        .contains("McpMethods");
            } finally {
                process.destroyForcibly();
            }
        }
    }

    // Harm: one overlong message holding memory without bound, or ending the session for good.
    @Test
    void aMessageOverItsBoundIsRefusedAndTheNextOneIsAnswered() throws Exception {
        try (var daemon = standIn(StandInDaemon.tasks1(), never(List.of()), Map.of())) {
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

    // Harm: a call the daemon accepts, such as a hand-in part of the size its contract recommends,
    // refused here before the daemon sees it.
    @Test
    void aHandInPartOfTwoMebibytesReachesTheDaemon() throws Exception {
        VarlinkServer.Method handIn = (parameters, replies) -> replies.last(Map.of("received", 0));
        try (var daemon =
                standIn(
                        StandInDaemon.tasks1Meant("HandIn"),
                        never(List.of()),
                        Map.of("HandIn", handIn))) {
            Ran ran =
                    run(
                            daemon,
                            request(
                                    1,
                                    "tools/call",
                                    Map.of(
                                            "name",
                                            "HandIn",
                                            "arguments",
                                            McpHttpServerTest.handInPart())));

            assertThat(ran.answers.get(0))
                    .as("answered, not refused: %s", ran.err)
                    .containsKey("result");
            assertThat(daemon.calls.stream().map(Map.Entry::getKey))
                    .as("the daemon got the call")
                    .containsExactly(McpMethods.METHOD, "org.fuin.porelo.Tasks1.HandIn");
        }
    }

    // Harm: a daemon answering a broken never-set and every marked tool being offered for it.
    @Test
    void aNeverSetThatIsMissingOrHoldsSomethingButNamesOffersNoTools() throws Exception {
        List<Map<String, Object>> broken =
                List.of(Map.of("reading", List.of("List")), Map.of("never", List.of("Panic", 42)));
        for (Map<String, Object> each : broken) {
            Path place = java.nio.file.Files.createTempDirectory(directory, "broken");
            Map<String, VarlinkServer.Method> methods =
                    Map.of("McpMethods", (parameters, replies) -> replies.last(each));
            try (var daemon = new StandInDaemon(place, StandInDaemon.tasks1(), methods)) {
                Ran ran = run(daemon, request(1, "tools/list", Map.of()));

                assertThat(toolNames(ran.answers.get(0))).as("no tools for %s", each).isEmpty();
                assertThat(ran.err).as("says why").contains("no tools offered");
            }
        }
    }

    private static Map<String, Object> answerTo(Ran ran, int id) {
        return ran.answers.stream()
                .filter(each -> new java.math.BigDecimal(id).equals(each.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no answer to " + id + ": " + ran.lines));
    }

    /** McpMethods' answer, as porelod gives it. */
    static Map<String, Object> never(List<String> never) {
        return Map.of("never", never);
    }

    private StandInDaemon standIn(
            String description, Map<String, Object> sets, Map<String, VarlinkServer.Method> more)
            throws IOException {
        Map<String, VarlinkServer.Method> methods = new java.util.HashMap<>(more);
        methods.put("McpMethods", (parameters, replies) -> replies.last(sets));
        return new StandInDaemon(directory, description, methods);
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
                        "tasks",
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
