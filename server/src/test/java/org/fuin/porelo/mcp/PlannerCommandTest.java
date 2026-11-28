package org.fuin.porelo.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.fuin.porelo.mcp.ContractToolsTest.request;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code porelo-mcp planner}: a person's own client gets one tool per operation the running planner
 * marks for agents, each call the planner's own answer, and no tools when no planner runs.
 */
class PlannerCommandTest {

    static final String TOKEN = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";

    @TempDir Path parent;

    // Harm: an agent offered an operation the planner keeps for the person, or a refusal lost.
    @Test
    void theToolsAreTheOperationsMarkedForAgentsAndACallIsThePlannersAnswer() throws Exception {
        final String description = description();
        try (StandInPlanner planner = new StandInPlanner(TOKEN)) {
            planner.openApi = description;
            final Path runtime = runtime(parent, planner.port());
            planner.body = "{\"id\":\"p#1\",\"state\":\"Backlog\"}";

            final Ran read =
                    run(
                            runtime,
                            request(1, "tools/list", Map.of()),
                            request(
                                    2,
                                    "tools/call",
                                    Map.of(
                                            "name",
                                            "getIssue",
                                            "arguments",
                                            Map.of("project", "p", "issue", "p#1"))));
            planner.status = 409;
            planner.body = "{\"title\":\"RunChanged\",\"detail\":\"the run changed\"}";
            final Ran refused =
                    run(
                            runtime,
                            request(
                                    3,
                                    "tools/call",
                                    Map.of(
                                            "name",
                                            "getIssue",
                                            "arguments",
                                            Map.of("project", "p", "issue", "p#1"))));

            assertThat(StdioCommandTest.toolNames(read.answers.get(0)))
                    .as("the operations marked true: %s", read.err)
                    .containsExactlyInAnyOrderElementsOf(meant(description))
                    .doesNotContain("saveMoves", "getDescription", "proveToken");
            assertThat(result(read.answers.get(1)).get("structuredContent"))
                    .as("the planner's answer")
                    .isEqualTo(Map.of("id", "p#1", "state", "Backlog"));
            assertThat(planner.seen)
                    .as("the call went to the issue's path, its segment encoded")
                    .anyMatch(line -> line.startsWith("GET /projects/p/issues/p%231 "));
            assertThat(result(refused.answers.get(0)).get("isError"))
                    .as("a refusal is a tool error")
                    .isEqualTo(true);
            assertThat(result(refused.answers.get(0)).get("structuredContent").toString())
                    .as("with the planner's name, status and words")
                    .contains("RunChanged")
                    .contains("409")
                    .contains("the run changed");
        }
    }

    // Harm: the planner's own answer reshaped by rules meant for the daemon's run results.
    @Test
    void aPlannerAnswerNamedResultIsShownWholeAsItCame() throws Exception {
        try (StandInPlanner planner = new StandInPlanner(TOKEN)) {
            planner.openApi = description();
            final Path runtime = runtime(parent, planner.port());
            planner.body = "{\"result\":\"x\",\"resultKind\":\"LAST_LINES\"}";

            final Ran ran =
                    run(
                            runtime,
                            request(
                                    1,
                                    "tools/call",
                                    Map.of(
                                            "name",
                                            "getIssue",
                                            "arguments",
                                            Map.of("project", "p", "issue", "p#1"))));

            assertThat(result(ran.answers.get(0)).get("content"))
                    .as("one text, the answer whole: %s", ran.err)
                    .isEqualTo(
                            List.of(
                                    Map.of(
                                            "type",
                                            "text",
                                            "text",
                                            "{\"result\":\"x\",\"resultKind\":\"LAST_LINES\"}")));
        }
    }

    // Harm: a description that is no JSON ends the person's session instead of offering none.
    @Test
    void aDescriptionThatIsNoJsonOffersNoToolsAndSaysSo() throws Exception {
        try (StandInPlanner planner = new StandInPlanner(TOKEN)) {
            planner.openApi = "{\"openapi\":";
            final Ran ran =
                    run(runtime(parent, planner.port()), request(1, "tools/list", Map.of()));

            assertThat(StdioCommandTest.toolNames(ran.answers.get(0))).as("no tools").isEmpty();
            assertThat(ran.err).as("says why").contains("no tools offered").contains("not JSON");
        }
    }

    // Harm: an agent told of tools that cannot answer, or a missing planner not named.
    @Test
    void withNoPlannerRunningItOffersNoToolsAndSaysSo() throws Exception {
        final Path runtime = owned(Files.createDirectory(parent.resolve("run")));

        final Ran ran = run(runtime, request(1, "tools/list", Map.of()));

        assertThat(StdioCommandTest.toolNames(ran.answers.get(0))).as("no tools").isEmpty();
        assertThat(ran.err).as("says why").contains("no tools offered").contains("NOT_RUNNING");
        assertThat(ran.code).as("ends with its input").isZero();
    }

    /** The operation ids the fixture marks {@code x-mcp: true}, read from its JSON alone. */
    @SuppressWarnings("unchecked")
    static List<String> meant(String description) {
        final List<String> meant = new ArrayList<>();
        final Map<String, Object> paths =
                (Map<String, Object>) ((Map<String, Object>) Json.parse(description)).get("paths");
        for (final Object item : paths.values()) {
            for (final Object operation : ((Map<String, Object>) item).values()) {
                if (operation instanceof Map<?, ?> fields
                        && Boolean.TRUE.equals(fields.get("x-mcp"))) {
                    meant.add((String) fields.get("operationId"));
                }
            }
        }
        assertThat(meant).as("the fixture marks operations for agents").hasSize(29);
        return meant;
    }

    static String description() throws IOException {
        try (var in = PlannerCommandTest.class.getResourceAsStream("/openapi/planner.json")) {
            if (in == null) {
                throw new IllegalStateException("The planner's description is not a test resource");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * A runtime directory whose address file names a planner on a port, as the planner writes it.
     */
    static Path runtime(Path parent, int port) throws IOException {
        final Path runtime = owned(Files.createDirectory(parent.resolve("run")));
        final Path directory = owned(Files.createDirectory(runtime.resolve("porelo-planner")));
        final Path file = Files.createFile(directory.resolve("planner.json"));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(file, "{\"port\":" + port + ",\"token\":\"" + TOKEN + "\"}");
        return runtime;
    }

    static Path owned(Path directory) throws IOException {
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        return directory;
    }

    private static Map<?, ?> result(Map<String, Object> answer) {
        return (Map<?, ?>) answer.get("result");
    }

    record Ran(int code, List<Map<String, Object>> answers, String err) {}

    private static Ran run(Path runtime, String... messages) throws Exception {
        final String java = ProcessHandle.current().info().command().orElseThrow();
        return run(
                List.of(
                        java,
                        "-cp",
                        System.getProperty("java.class.path"),
                        Main.class.getName(),
                        "planner"),
                runtime,
                messages);
    }

    /**
     * Runs a command over pipes with a runtime directory, one message per line, and reads it all.
     */
    @SuppressWarnings("unchecked")
    static Ran run(List<String> command, Path runtime, String... messages) throws Exception {
        final ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("XDG_RUNTIME_DIR", runtime.toString());
        final Process process = builder.start();
        try (OutputStream in = process.getOutputStream()) {
            for (final String message : messages) {
                in.write((message + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        final String out =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        final String err =
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(20, TimeUnit.SECONDS)).as("it ends with its input").isTrue();
        final List<Map<String, Object>> answers = new ArrayList<>();
        for (final String line : out.lines().toList()) {
            answers.add((Map<String, Object>) Json.parseDecimals(line));
        }
        assertThat(answers).as("one answer per request: %s", err).hasSize(messages.length);
        return new Ran(process.exitValue(), answers, err);
    }
}
