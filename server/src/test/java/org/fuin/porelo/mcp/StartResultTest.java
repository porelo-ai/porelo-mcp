package org.fuin.porelo.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.fuin.porelo.mcp.ContractToolsTest.request;
import static org.fuin.porelo.mcp.ContractToolsTest.result;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A reply that carries the run's {@code result} shows it as the tool's text, since an agent reads
 * the text first: what the run answered, said to be the run's last lines or cut where it is, and
 * the rest of the reply after it. The structured content stays the reply as it came.
 */
class StartResultTest {

    @TempDir Path directory;

    // Harm: an agent that started a task with a prompt reads exitCode 0 and never learns what the
    // run answered.
    @Test
    void aRunsAnswerIsTheToolsTextAndTheRestOfTheReplyFollows() throws Exception {
        Map<String, Object> reply = reply("main, 5 files in the repository root", "ANSWER", null);
        try (var daemon = standIn(reply)) {
            Map<?, ?> answer = start(daemon);

            assertThat(texts(answer))
                    .as("the answer first, as the run gave it; then the rest of the reply")
                    .hasSize(2);
            assertThat(texts(answer).getFirst()).isEqualTo("main, 5 files in the repository root");
            assertThat(Json.parseDecimals(texts(answer).get(1)))
                    .as("the reply without what the first text already says")
                    .isEqualTo(Json.parseDecimals("{\"exitCode\":0,\"output\":[]}"));
            assertThat(answer.get("structuredContent"))
                    .as("the reply as it came, result and all")
                    .isEqualTo(Json.parseDecimals(Json.write(reply)));
            assertThat(answer.get("isError")).isEqualTo(false);
        }
    }

    // Harm: an agent that takes the run's last lines for its answer.
    @Test
    void theRunsLastLinesAreSaidToBeNoAnswer() throws Exception {
        try (var daemon = standIn(reply("step 3 of 4\ntimed out", "LAST_LINES", null))) {
            String text = texts(start(daemon)).getFirst();

            assertThat(text)
                    .as("a line saying what these are, then the lines")
                    .isEqualTo(
                            "The run gave no answer; these are its last lines:\n"
                                    + "step 3 of 4\ntimed out");
        }
    }

    // Harm: an agent that reads a cut answer as the whole of it.
    @Test
    void aCutResultSaysItWasCut() throws Exception {
        try (var daemon = standIn(reply("the first 16 KiB", "ANSWER", true))) {
            String text = texts(start(daemon)).getFirst();

            assertThat(text)
                    .isEqualTo("the first 16 KiB\n[cut: the result was longer than 16 KiB]");
        }
    }

    // Harm: a kind Porelo adds later shown as if it were the run's answer.
    @Test
    void aKindThisBuildDoesNotKnowIsNamed() throws Exception {
        try (var daemon = standIn(reply("x", "SUMMARY", null))) {
            assertThat(texts(start(daemon)).getFirst())
                    .isEqualTo("The result is of the kind SUMMARY:\nx");
        }
    }

    // Harm: every other reply changed by a rule meant for one field.
    @Test
    void aReplyWithoutAResultStaysOneTextOfTheWholeReply() throws Exception {
        try (var daemon = standIn(Map.of("exitCode", 0, "output", List.of("ready")))) {
            Map<?, ?> answer = start(daemon);

            assertThat(texts(answer)).hasSize(1);
            assertThat(Json.parseDecimals(texts(answer).getFirst()))
                    .isEqualTo(answer.get("structuredContent"));
        }
    }

    // Harm: an agent that streams its start sees the answer only inside a JSON line.
    @Test
    void aStreamedStartEndsWithTheAnswerAsItsText() throws Exception {
        Map<String, Object> last = reply("done: 3 commits", "ANSWER", null);
        VarlinkServer.Method start =
                (parameters, replies) -> {
                    if (replies.streaming()) {
                        replies.more(Map.of("runLine", "{\"type\":\"assistant\"}"));
                    }
                    replies.last(last);
                };
        try (var daemon =
                new StandInDaemon(directory, StandInDaemon.tasks1(), Map.of("Start", start))) {
            List<String> sent = new CopyOnWriteArrayList<>();
            McpSession session = session(daemon);
            try (Streams streams =
                    new Streams(
                            message -> {
                                sent.add(message);
                                return true;
                            })) {
                Map<String, Object> meta = Map.of("progressToken", "p-1");
                Map<String, Object> parameters = new LinkedHashMap<>();
                parameters.put("name", "Start");
                parameters.put("arguments", Map.of("task", "t-1", "prompt", "go"));
                parameters.put("_meta", meta);
                assertThat(session.handle(request(1, "tools/call", parameters), streams)).isNull();
                streams.awaitAll();
            }

            Map<?, ?> answer = (Map<?, ?>) result(sent.getLast());
            assertThat(sent).as("each reply as progress, then the answer").hasSize(3);
            assertThat(texts(answer).getFirst()).isEqualTo("done: 3 commits");
        }
    }

    private static Map<String, Object> reply(
            String result, String kind, @org.jspecify.annotations.Nullable Boolean cut) {
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("exitCode", 0);
        reply.put("output", List.of());
        reply.put("result", result);
        reply.put("resultKind", kind);
        if (cut != null) {
            reply.put("resultCut", cut);
        }
        return reply;
    }

    private StandInDaemon standIn(Map<String, Object> reply) throws Exception {
        VarlinkServer.Method start = (parameters, replies) -> replies.last(reply);
        return new StandInDaemon(directory, StandInDaemon.tasks1(), Map.of("Start", start));
    }

    private static Map<?, ?> start(StandInDaemon daemon) throws Exception {
        String call =
                request(
                        1,
                        "tools/call",
                        Map.of(
                                "name",
                                "Start",
                                "arguments",
                                Map.of("task", "t-1", "prompt", "go")));
        return (Map<?, ?>) result(session(daemon).handle(call));
    }

    private static McpSession session(StandInDaemon daemon) throws Exception {
        return new McpSession(
                Tool.of(VarlinkIdl.parse(StandInDaemon.tasks1())),
                new SocketDaemon(daemon.socket()),
                "0");
    }

    private static List<String> texts(Map<?, ?> answer) {
        return ((List<?>) answer.get("content"))
                .stream().map(each -> (String) ((Map<?, ?>) each).get("text")).toList();
    }
}
