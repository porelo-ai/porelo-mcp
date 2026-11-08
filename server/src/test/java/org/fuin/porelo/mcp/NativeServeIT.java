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
            ServeCommandTest.Started started = new ServeCommandTest.Started(process);
            started.awaitReady();

            String answer =
                    request(
                            socket,
                            McpHttpServerTest.post(
                                    McpHttpServerTest.LIST, "Bearer " + McpHttpServerTest.TOKEN));
            String refused =
                    request(socket, McpHttpServerTest.post(McpHttpServerTest.LIST, "Bearer wrong"));
            process.destroy();

            assertThat(McpHttpServerTest.status(answer))
                    .as("the tools, for the own user with the token")
                    .isEqualTo(200);
            assertThat(answer).as("listed from the interface").contains("\"name\":\"List\"");
            assertThat(McpHttpServerTest.status(refused)).as("a wrong token").isEqualTo(401);
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).as("it exits").isTrue();
            assertThat(process.exitValue()).as("stopped on SIGTERM").isEqualTo(0);
            assertThat(Files.exists(socket)).as("its socket removed").isFalse();
        }
    }

    // Harm: a native stdio that writes something else than protocol, or offers tools it should not.
    @Test
    void stdioListsOnlyTheDaemonsSets() throws Exception {
        Map<String, org.fuin.porelo.wire.varlink.VarlinkServer.Method> methods =
                Map.of(
                        "McpMethods",
                        (parameters, replies) ->
                                replies.last(
                                        StdioCommandTest.sets(
                                                java.util.List.of("List"),
                                                java.util.List.of(),
                                                java.util.List.of("Panic"),
                                                java.util.List.of())));
        try (var daemon =
                new StandInDaemon(
                        directory,
                        StandInDaemon.tasks1() + StdioCommandTest.MCP_METHODS,
                        methods)) {
            Process process =
                    new ProcessBuilder(
                                    executable(),
                                    "stdio",
                                    "--daemon-socket",
                                    daemon.socket().toString())
                            .start();
            try (OutputStream in = process.getOutputStream()) {
                in.write(
                        (ContractToolsTest.request(1, "tools/list", Map.of()) + "\n")
                                .getBytes(StandardCharsets.UTF_8));
            }
            String out =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            assertThat(process.waitFor(20, TimeUnit.SECONDS)).as("it ends with its input").isTrue();
            assertThat(out.lines().toList()).as("one line of protocol").hasSize(1);
            @SuppressWarnings("unchecked")
            Map<String, Object> answer =
                    (Map<String, Object>) org.fuin.porelo.wire.Json.parseDecimals(out.strip());
            assertThat(StdioCommandTest.toolNames(answer))
                    .as("the reading set alone")
                    .containsExactly("List");
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
