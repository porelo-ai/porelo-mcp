package org.fuin.porelo.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.fuin.porelo.mcp.ContractToolsTest.request;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A method that answers as a stream, over stdio: its replies as MCP progress when the client asks
 * with a progress token, and ended when the client cancels.
 */
class StreamsTest {

    @TempDir Path directory;

    private final List<Process> processes = new ArrayList<>();

    // Harm: a stdio left running by a failing test, holding a daemon connection for good.
    @AfterEach
    void endEveryProcessStarted() throws InterruptedException {
        for (Process process : processes) {
            process.destroyForcibly();
            process.waitFor(20, TimeUnit.SECONDS);
        }
    }

    // Harm: an agent following a log or a build sees nothing until it ends, or sees only its end.
    @Test
    void withAProgressTokenEachReplyIsProgressAndTheLastIsAlsoTheResult() throws Exception {
        VarlinkServer.Method tail =
                (parameters, replies) -> {
                    if (!replies.streaming()) {
                        replies.last(Map.of("line", "once"));
                        return;
                    }
                    replies.more(Map.of("line", "one"));
                    replies.more(Map.of("line", "two"));
                    replies.last(Map.of("line", "three"));
                };
        try (var daemon = standIn(Map.of("Tail", tail))) {
            Stdio stdio = start(daemon);

            stdio.send(call(1, "Tail", Map.of("task", "t-1"), "p-1"));
            List<Map<String, Object>> got = stdio.until(1);
            stdio.send(call(2, "Tail", Map.of("task", "t-1"), null));
            Map<String, Object> plain = stdio.until(2).getLast();

            List<Map<String, Object>> progress =
                    got.stream()
                            .filter(each -> "notifications/progress".equals(each.get("method")))
                            .toList();
            assertThat(progress).as("one progress per reply, before the result").hasSize(3);
            assertThat(progress.stream().map(each -> params(each).get("progressToken")))
                    .as("each with the client's token")
                    .containsOnly("p-1");
            assertThat(progress.stream().map(each -> params(each).get("progress")))
                    .as("counting up")
                    .containsExactly(new BigDecimal(1), new BigDecimal(2), new BigDecimal(3));
            assertThat(progress.stream().map(each -> (String) params(each).get("message")))
                    .as("each reply as JSON text")
                    .containsExactly(
                            "{\"line\":\"one\"}", "{\"line\":\"two\"}", "{\"line\":\"three\"}");
            assertThat(structured(got.getLast()))
                    .as("the last reply, the result")
                    .isEqualTo(Map.of("line", "three"));
            assertThat(structured(plain))
                    .as("without a token, one answer as before")
                    .isEqualTo(Map.of("line", "once"));
        }
    }

    // Harm: an agent that stops watching leaves the daemon streaming to nobody, and its stdio
    // deaf to every request after it.
    @Test
    void aCancelledStreamEndsTheDaemonsCallGetsNoAnswerAndTheNextRequestIsAnswered()
            throws Exception {
        CountDownLatch gone = new CountDownLatch(1);
        VarlinkServer.Method watch =
                (parameters, replies) -> {
                    replies.more(Map.of("tasks", List.of()));
                    // Endless, as Watch is: it sends only on a change, and asks between its
                    // waits whether the client is still there.
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
        try (var daemon = standIn(Map.of("Watch", watch))) {
            Stdio stdio = start(daemon);

            stdio.send(call(1, "Watch", Map.of(), "w-1"));
            Map<String, Object> first = stdio.next();
            stdio.send(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\","
                            + "\"params\":{\"requestId\":1}}");
            boolean ended = gone.await(10, TimeUnit.SECONDS);
            stdio.send(request(2, "ping", Map.of()));
            List<Map<String, Object>> after = stdio.until(2);

            assertThat(first.get("method"))
                    .as("the first list as progress")
                    .isEqualTo("notifications/progress");
            assertThat(ended).as("the daemon's call ended with the cancel").isTrue();
            assertThat(after)
                    .as("the ping answered, and nothing for the cancelled request: %s", after)
                    .singleElement()
                    .satisfies(
                            each ->
                                    assertThat(each.get("id"))
                                            .as("the ping's")
                                            .isEqualTo(new BigDecimal(2)));
        }
    }

    // Harm: one client opening stream after stream until the process has no thread or descriptor
    // left.
    @Test
    void atMostSixtyFourStreamsRunAtOnceAndOneMoreIsRefusedNotQueued() throws Exception {
        VarlinkServer.Method watch =
                (parameters, replies) -> {
                    replies.more(Map.of("tasks", List.of()));
                    while (replies.open()) {
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                };
        try (var daemon = standIn(Map.of("Watch", watch))) {
            Stdio stdio = start(daemon);
            for (int i = 1; i <= Streams.MAX_STREAMS; i++) {
                stdio.send(call(i, "Watch", Map.of(), "w-" + i));
            }
            List<Map<String, Object>> first = new ArrayList<>();
            for (int i = 1; i <= Streams.MAX_STREAMS; i++) {
                first.add(stdio.next());
            }

            stdio.send(call(1000, "Watch", Map.of(), "w-1000"));
            Map<String, Object> refused = stdio.until(1000).getLast();

            assertThat(first)
                    .as("each of the 64 streaming")
                    .allSatisfy(
                            each ->
                                    assertThat(each.get("method"))
                                            .as("a progress")
                                            .isEqualTo("notifications/progress"));
            assertThat(((Map<?, ?>) refused.get("error")).get("code"))
                    .as("the 65th refused at once")
                    .isEqualTo(new BigDecimal(-32600));
        }
    }

    private StandInDaemon standIn(Map<String, VarlinkServer.Method> more) throws IOException {
        Map<String, VarlinkServer.Method> methods = new java.util.HashMap<>(more);
        Map<String, Object> sets =
                StdioCommandTest.sets(
                        List.of("List", "Tail", "Watch"), List.of(), List.of(), List.of());
        methods.put("McpMethods", (parameters, replies) -> replies.last(sets));
        return new StandInDaemon(
                directory, StandInDaemon.tasks1() + StdioCommandTest.MCP_METHODS, methods);
    }

    private static String call(int id, String tool, Map<String, Object> arguments, String token) {
        Map<String, Object> parameters = new java.util.LinkedHashMap<>();
        parameters.put("name", tool);
        parameters.put("arguments", arguments);
        if (token != null) {
            parameters.put("_meta", Map.of("progressToken", token));
        }
        return request(id, "tools/call", parameters);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> params(Map<String, Object> message) {
        return (Map<String, Object>) message.get("params");
    }

    private static Object structured(Map<String, Object> answer) {
        return ((Map<?, ?>) answer.get("result")).get("structuredContent");
    }

    private Stdio start(StandInDaemon daemon) throws IOException {
        String java = ProcessHandle.current().info().command().orElseThrow();
        Process process =
                new ProcessBuilder(
                                java,
                                "-cp",
                                System.getProperty("java.class.path"),
                                Main.class.getName(),
                                "stdio",
                                "--daemon-socket",
                                daemon.socket().toString())
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
        processes.add(process);
        return new Stdio(process);
    }

    /** A stdio process spoken to line by line, each line it writes read as it comes. */
    private static final class Stdio {
        private final OutputStream in;
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

        Stdio(Process process) {
            in = process.getOutputStream();
            Thread.ofPlatform()
                    .daemon()
                    .start(
                            () -> {
                                try (BufferedReader out =
                                        process.inputReader(StandardCharsets.UTF_8)) {
                                    String line;
                                    while ((line = out.readLine()) != null) {
                                        lines.add(line);
                                    }
                                } catch (IOException ex) {
                                    // The process ended.
                                }
                            });
        }

        void send(String message) throws IOException {
            in.write((message + "\n").getBytes(StandardCharsets.UTF_8));
            in.flush();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> next() throws InterruptedException {
            String line = lines.poll(10, TimeUnit.SECONDS);
            assertThat(line).as("a line within 10 s").isNotNull();
            return (Map<String, Object>) Json.parseDecimals(line);
        }

        /** Every message up to and including the answer to this id. */
        List<Map<String, Object>> until(int id) throws InterruptedException {
            List<Map<String, Object>> got = new ArrayList<>();
            while (true) {
                Map<String, Object> each = next();
                got.add(each);
                if (new BigDecimal(id).equals(each.get("id"))) {
                    return got;
                }
            }
        }
    }
}
