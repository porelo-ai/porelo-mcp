package org.fuin.porelo.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The native executable serves as the classes do: a native image drops what it was not told it
 * needs.
 */
class NativeServeIT {

    @TempDir Path directory;

    // Harm: a native build whose peer check, token or signal handling was left out, found only on a
    // machine.
    @Test
    void serveAnswersTheOwnUserWithTheTokenAndStopsOnSigterm() throws Exception {
        try (var daemon = new StandInDaemon(directory, StandInDaemon.tasks1(), Map.of())) {
            Path mcp = Files.createDirectory(directory.resolve("mcp"));
            Files.setPosixFilePermissions(mcp, PosixFilePermissions.fromString("rwx------"));
            Path socket = mcp.resolve("t.sock");
            Process process =
                    new ProcessBuilder(
                                    executable(),
                                    "serve",
                                    "--task",
                                    "t-1",
                                    "--socket",
                                    socket.toString(),
                                    "--daemon-socket",
                                    daemon.socket().toString())
                            .start();
            try (OutputStream in = process.getOutputStream()) {
                in.write((McpHttpServerTest.TOKEN + "\n").getBytes(StandardCharsets.US_ASCII));
            }
            try {
                ServeCommandTest.Started started = new ServeCommandTest.Started(process);
                started.awaitReady();

                String answer =
                        request(
                                socket,
                                McpHttpServerTest.post(
                                        McpHttpServerTest.LIST,
                                        "Bearer " + McpHttpServerTest.TOKEN));
                String refused =
                        request(
                                socket,
                                McpHttpServerTest.post(McpHttpServerTest.LIST, "Bearer wrong"));
                process.destroy();

                assertThat(McpHttpServerTest.status(answer))
                        .as("the tools, for the own user with the token")
                        .isEqualTo(200);
                assertThat(answer).as("listed from the interface").contains("\"name\":\"List\"");
                assertThat(McpHttpServerTest.status(refused)).as("a wrong token").isEqualTo(401);
                assertThat(process.waitFor(20, TimeUnit.SECONDS)).as("it exits").isTrue();
                assertThat(process.exitValue()).as("stopped on SIGTERM").isEqualTo(0);
                assertThat(Files.exists(socket)).as("its socket removed").isFalse();
            } finally {
                // Ended whatever an assertion above saw, so no serve outlives the test.
                process.destroyForcibly();
            }
        }
    }

    // Harm: a native stdio that writes something else than protocol, offers tools it should not,
    // names no version, or fails at its end.
    @Test
    void stdioNamesItsVersionListsOnlyTheMethodsMarkedForAgentsAndEndsWithItsInput()
            throws Exception {
        Map<String, org.fuin.porelo.wire.varlink.VarlinkServer.Method> methods =
                Map.of(
                        "McpMethods",
                        (parameters, replies) ->
                                replies.last(StdioCommandTest.never(java.util.List.of("Panic"))));
        try (var daemon = new StandInDaemon(directory, StandInDaemon.tasks1(), methods)) {
            Process process =
                    new ProcessBuilder(
                                    executable(),
                                    "tasks",
                                    "--daemon-socket",
                                    daemon.socket().toString())
                            .start();
            try (OutputStream in = process.getOutputStream()) {
                String initialize =
                        ContractToolsTest.request(
                                1,
                                "initialize",
                                Map.of(
                                        "protocolVersion",
                                        "2025-11-25",
                                        "capabilities",
                                        Map.of(),
                                        "clientInfo",
                                        Map.of("name", "test", "version", "0")));
                String list = ContractToolsTest.request(2, "tools/list", Map.of());
                in.write((initialize + "\n" + list + "\n").getBytes(StandardCharsets.UTF_8));
            }
            String out =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            assertThat(process.waitFor(20, TimeUnit.SECONDS)).as("it ends with its input").isTrue();
            assertThat(process.exitValue()).as("and exits 0 then").isZero();
            List<String> lines = out.lines().toList();
            assertThat(lines).as("one line of protocol per request").hasSize(2);
            @SuppressWarnings("unchecked")
            Map<String, Object> initialized =
                    (Map<String, Object>) org.fuin.porelo.wire.Json.parseDecimals(lines.get(0));
            @SuppressWarnings("unchecked")
            Map<String, Object> answer =
                    (Map<String, Object>) org.fuin.porelo.wire.Json.parseDecimals(lines.get(1));
            assertThat(
                            ((Map<?, ?>) ((Map<?, ?>) initialized.get("result")).get("serverInfo"))
                                    .get("version"))
                    .as("the version the build gave it")
                    .isEqualTo(System.getProperty("mcp.version"));
            assertThat(StdioCommandTest.toolNames(answer))
                    .as("the methods the pin marks for agents")
                    .containsExactlyElementsOf(StandInDaemon.meantIn(StandInDaemon.tasks1()));
        }
    }

    // Harm: a native build whose planner client - its HTTP, its signatures - was left out.
    @Test
    void plannerOffersThePlannersOperationsMarkedForAgentsAndCallsThem() throws Exception {
        String described = PlannerCommandTest.description();
        try (StandInPlanner planner = new StandInPlanner(PlannerCommandTest.TOKEN)) {
            planner.openApi = described;
            planner.body = "{\"id\":\"p#1\"}";
            Path runtime = PlannerCommandTest.runtime(directory, planner.port());

            PlannerCommandTest.Ran ran =
                    PlannerCommandTest.run(
                            List.of(executable(), "planner"),
                            runtime,
                            ContractToolsTest.request(1, "tools/list", Map.of()),
                            ContractToolsTest.request(
                                    2,
                                    "tools/call",
                                    Map.of(
                                            "name",
                                            "getIssue",
                                            "arguments",
                                            Map.of("project", "p", "issue", "p#1"))));

            assertThat(StdioCommandTest.toolNames(ran.answers().get(0)))
                    .as("the operations marked for agents: %s", ran.err())
                    .containsExactlyInAnyOrderElementsOf(PlannerCommandTest.meant(described));
            assertThat(((Map<?, ?>) ran.answers().get(1).get("result")).get("structuredContent"))
                    .as("the planner's signed answer, its proof checked")
                    .isEqualTo(Map.of("id", "p#1"));
        }
    }

    private static String executable() {
        return System.getProperty("mcp.executable");
    }

    private static String request(Path socket, String request) throws Exception {
        try (SocketChannel client = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            client.connect(UnixDomainSocketAddress.of(socket));
            client.write(ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8)));
            return new String(
                    Channels.newInputStream(client).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
