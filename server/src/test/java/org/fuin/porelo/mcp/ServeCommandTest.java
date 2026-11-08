package org.fuin.porelo.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code porelo-mcp serve} as the daemon starts it: in a process of its own, by the binding's order
 * and exit codes.
 */
class ServeCommandTest {

    static final String TOKEN = McpHttpServerTest.TOKEN;

    @TempDir Path directory;

    // Harm: a refused start leaving a socket behind, or never saying to the daemon that it was
    // refused.
    @Test
    void aTokenThatIsRefusedExitsTwoBeforeAnySocketIsBound() throws Exception {
        List<String> refused =
                List.of(
                        "",
                        "short\n",
                        TOKEN,
                        TOKEN.replace('0', ' ') + "\n",
                        TOKEN + "\u0001\n",
                        "a".repeat(1024) + "\n");
        try (var daemon = new StandInDaemon(directory, StandInDaemon.tasks1(), Map.of())) {
            for (String token : refused) {
                Path socket = mcpDirectory().resolve("t.sock");
                Started started = start(socket, daemon.socket(), token);

                assertThat(started.process.waitFor(20, TimeUnit.SECONDS)).as("it exits").isTrue();
                assertThat(started.process.exitValue())
                        .as("token %s exits 2", token.length())
                        .isEqualTo(2);
                assertThat(Files.exists(socket)).as("no socket bound").isFalse();
                assertThat(started.output()).as("no ready").doesNotContain("ready");
            }
        }
    }

    // Harm: the token leaking into a log, an error or a command line, where other readers find it.
    @Test
    void theTokenAppearsInNoOutputAndNoCommandLine() throws Exception {
        try (var daemon = new StandInDaemon(directory, StandInDaemon.tasks1(), Map.of())) {
            Path socket = mcpDirectory().resolve("t.sock");
            Started started = start(socket, daemon.socket(), TOKEN + "\n");
            started.awaitReady();

            String commandLine =
                    Files.readString(Path.of("/proc/" + started.process.pid() + "/cmdline"));
            String environment =
                    new String(
                            Files.readAllBytes(
                                    Path.of("/proc/" + started.process.pid() + "/environ")),
                            StandardCharsets.ISO_8859_1);
            started.process.destroy();
            started.process.waitFor(20, TimeUnit.SECONDS);

            assertThat(commandLine).as("not in the command line").doesNotContain(TOKEN);
            assertThat(environment).as("not in the environment").doesNotContain(TOKEN);
            assertThat(started.output() + started.errors())
                    .as("not in any output, not even in part")
                    .doesNotContain(TOKEN)
                    .doesNotContain(TOKEN.substring(0, 16));
        }
    }

    // Harm: a daemon told the server is up when it could never reach the daemon; a socket left
    // after that.
    @Test
    void anUnreachableDaemonExitsThreeBeforeReadyWithNoSocketBound() throws Exception {
        Path socket = mcpDirectory().resolve("t.sock");
        Started started = start(socket, directory.resolve("no-daemon.sock"), TOKEN + "\n");

        assertThat(started.process.waitFor(20, TimeUnit.SECONDS)).as("it exits").isTrue();
        assertThat(started.process.exitValue()).as("the daemon unreachable").isEqualTo(3);
        assertThat(Files.exists(socket)).as("no socket bound").isFalse();
        assertThat(started.output()).as("no ready").doesNotContain("ready");
        assertThat(started.errors()).as("names the socket it tried").contains("no-daemon.sock");
    }

    // Harm: someone's file at the socket's path replaced, or a socket in a directory others can
    // enter.
    @Test
    void anExistingPathOrAnOpenDirectoryExitsTwoAndIsLeftAsItWas() throws Exception {
        try (var daemon = new StandInDaemon(directory, StandInDaemon.tasks1(), Map.of())) {
            Path socket = mcpDirectory().resolve("t.sock");
            Files.writeString(socket, "somebody's file");
            Started onAFile = start(socket, daemon.socket(), TOKEN + "\n");
            assertThat(onAFile.process.waitFor(20, TimeUnit.SECONDS)).as("it exits").isTrue();
            assertThat(onAFile.process.exitValue()).as("refused on an existing path").isEqualTo(2);
            assertThat(Files.readString(socket)).as("left as it was").isEqualTo("somebody's file");

            Path open = Files.createDirectory(directory.resolve("open"));
            Files.setPosixFilePermissions(open, PosixFilePermissions.fromString("rwxr-xr-x"));
            Started inOpen = start(open.resolve("t.sock"), daemon.socket(), TOKEN + "\n");
            assertThat(inOpen.process.waitFor(20, TimeUnit.SECONDS)).as("it exits").isTrue();
            assertThat(inOpen.process.exitValue()).as("a directory of mode 0755").isEqualTo(2);
            assertThat(inOpen.errors()).as("names the mode it found").contains("rwxr-xr-x");
        }
    }

    // Harm: a process another program could reach over the network, or a stop read by the daemon as
    // a failure.
    @Test
    void itServesWithNoTcpSocketAndStopsCleanlyOnSigterm() throws Exception {
        try (var daemon = new StandInDaemon(directory, StandInDaemon.tasks1(), Map.of())) {
            Path socket = mcpDirectory().resolve("t.sock");
            Started started = start(socket, daemon.socket(), TOKEN + "\n");
            started.awaitReady();
            long pid = started.process.pid();

            String answer =
                    request(
                            socket,
                            McpHttpServerTest.post(McpHttpServerTest.LIST, "Bearer " + TOKEN));
            assertThat(McpHttpServerTest.status(answer))
                    .as("the kernel's peer check lets the person's own user in")
                    .isEqualTo(200);
            List<Long> tcp = tcpSockets(pid);
            assertThat(socketInodes(pid))
                    .as("the check sees the process's sockets at all")
                    .isNotEmpty();
            started.process.destroy();

            assertThat(tcp).as("no socket of the process is a TCP socket").isEmpty();
            assertThat(started.process.waitFor(20, TimeUnit.SECONDS)).as("it exits").isTrue();
            assertThat(started.process.exitValue()).as("stopped on SIGTERM").isEqualTo(0);
            assertThat(Files.exists(socket)).as("its own socket removed").isFalse();
        }
    }

    private static String request(Path socket, String request) throws IOException {
        try (var client =
                java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
            client.connect(java.net.UnixDomainSocketAddress.of(socket));
            client.write(java.nio.ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8)));
            return new String(
                    java.nio.channels.Channels.newInputStream(client).readAllBytes(),
                    StandardCharsets.UTF_8);
        }
    }

    /** The inodes of the process's socket descriptors that its network namespace lists as TCP. */
    static List<Long> tcpSockets(long pid) throws IOException {
        List<Long> sockets = socketInodes(pid);
        List<Long> tcp = new ArrayList<>();
        for (String table : List.of("tcp", "tcp6")) {
            for (String line : Files.readAllLines(Path.of("/proc/" + pid + "/net/" + table))) {
                String[] fields = line.trim().split("\\s+");
                if (fields.length > 9
                        && fields[9].chars().allMatch(Character::isDigit)
                        && sockets.contains(Long.parseLong(fields[9]))) {
                    tcp.add(Long.parseLong(fields[9]));
                }
            }
        }
        return tcp;
    }

    static List<Long> socketInodes(long pid) throws IOException {
        List<Long> inodes = new ArrayList<>();
        try (var fds = Files.list(Path.of("/proc/" + pid + "/fd"))) {
            for (Path fd : fds.toList()) {
                try {
                    String target = Files.readSymbolicLink(fd).toString();
                    if (target.startsWith("socket:[")) {
                        inodes.add(Long.parseLong(target.substring(8, target.length() - 1)));
                    }
                } catch (IOException gone) {
                    // A descriptor closed while listed.
                }
            }
        }
        return inodes;
    }

    private Path mcpDirectory() throws IOException {
        Path mcp = Files.createTempDirectory(directory, "mcp");
        Files.setPosixFilePermissions(mcp, PosixFilePermissions.fromString("rwx------"));
        return mcp;
    }

    /** Every process a test started, ended after it whatever the test saw. */
    private final java.util.List<Process> processes = new java.util.ArrayList<>();

    // Harm: a serve that a failing test never stopped, left listening on the machine for good.
    @AfterEach
    void endEveryProcessStarted() throws InterruptedException {
        for (Process process : processes) {
            process.destroyForcibly();
            process.waitFor(20, TimeUnit.SECONDS);
        }
    }

    private Started start(Path socket, Path daemonSocket, String token) throws IOException {
        String java = ProcessHandle.current().info().command().orElseThrow();
        ProcessBuilder builder =
                new ProcessBuilder(
                        java,
                        "-cp",
                        System.getProperty("java.class.path"),
                        Main.class.getName(),
                        "serve",
                        "--task",
                        "t-1",
                        "--socket",
                        socket.toString(),
                        "--daemon-socket",
                        daemonSocket.toString());
        builder.environment()
                .keySet()
                .removeIf(name -> !name.equals("PATH") && !name.equals("HOME"));
        Process process = builder.start();
        processes.add(process);
        try (OutputStream in = process.getOutputStream()) {
            in.write(token.getBytes(StandardCharsets.ISO_8859_1));
        }
        return new Started(process);
    }

    /** A started process, its standard output and error read as they come. */
    static final class Started {
        final Process process;
        private final StringBuilder out = new StringBuilder();
        private final StringBuilder err = new StringBuilder();
        private final Thread outReader;
        private final Thread errReader;

        Started(Process process) {
            this.process = process;
            outReader =
                    Thread.ofPlatform()
                            .daemon()
                            .start(() -> drain(process.inputReader(StandardCharsets.UTF_8), out));
            errReader =
                    Thread.ofPlatform()
                            .daemon()
                            .start(() -> drain(process.errorReader(StandardCharsets.UTF_8), err));
        }

        void awaitReady() throws InterruptedException {
            for (int i = 0; i < 400; i++) {
                synchronized (out) {
                    if (out.toString().contains("ready\n")) {
                        return;
                    }
                }
                if (!process.isAlive()) {
                    break;
                }
                Thread.sleep(50);
            }
            throw new AssertionError("no ready line; errors: " + errors());
        }

        String output() throws InterruptedException {
            outReader.join(5_000);
            synchronized (out) {
                return out.toString();
            }
        }

        String errors() {
            try {
                errReader.join(process.isAlive() ? 100 : 5_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            synchronized (err) {
                return err.toString();
            }
        }

        private static void drain(BufferedReader reader, StringBuilder into) {
            try (reader) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (into) {
                        into.append(line).append('\n');
                    }
                }
            } catch (IOException ex) {
                // The process ended.
            }
        }
    }
}
