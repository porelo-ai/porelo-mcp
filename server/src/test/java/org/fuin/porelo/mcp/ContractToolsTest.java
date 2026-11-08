package org.fuin.porelo.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every method of the daemon's contract reaches an agent as a tool, generated, never listed by
 * hand.
 */
class ContractToolsTest {

    @TempDir Path directory;

    // Harm: a method of the contract the agent cannot reach, or reaches under a wrong name.
    @Test
    void everyMethodTheRealInterfaceDeclaresIsATool() throws Exception {
        String description = StandInDaemon.tasks1();
        List<String> declared = methodsDeclaredIn(description);
        assertThat(declared)
                .as(
                        "the reference is the real interface's own method lines, not an empty or made-up set")
                .contains("List", "Start", "Stop", "Panic", "HandIn")
                .hasSizeGreaterThan(100)
                .doesNotHaveDuplicates();

        List<Tool> tools = Tool.of(VarlinkIdl.parse(description));

        assertThat(tools.stream().map(Tool::name))
                .as("one tool per method, in the interface's order")
                .containsExactlyElementsOf(declared);
        assertThat(tools.stream().map(Tool::method))
                .as("each tool calls its method by the interface's full name")
                .allMatch(method -> method.startsWith("org.fuin.porelo.Tasks1."));
    }

    // Harm: a method added to the contract that never reaches the agent until someone edits this
    // code.
    @Test
    void aMethodAddedToTheDaemonsDescriptionIsOfferedWithNoChangeHere() throws Exception {
        String added =
                StandInDaemon.tasks1()
                        + "\n# Says hello, in a version of the contract this code has never seen.\n"
                        + "method HelloFromTheFuture(name: string) -> (greeting: string)\n";
        try (var daemon = new StandInDaemon(directory, added, Map.of())) {
            String served = new SocketDaemon(daemon.socket()).describe("org.fuin.porelo.Tasks1");
            McpSession session =
                    new McpSession(
                            Tool.of(VarlinkIdl.parse(served)),
                            new SocketDaemon(daemon.socket()),
                            "0");

            Map<?, ?> listed =
                    (Map<?, ?>) result(session.handle(request(1, "tools/list", Map.of())));

            List<?> tools = (List<?>) listed.get("tools");
            assertThat(tools)
                    .as("the new method is a tool, with its comment and its parameter")
                    .anySatisfy(
                            tool -> {
                                Map<?, ?> each = (Map<?, ?>) tool;
                                assertThat(each.get("name"))
                                        .as("the new method's name")
                                        .isEqualTo("HelloFromTheFuture");
                                assertThat(each.get("description"))
                                        .as("its comment")
                                        .isEqualTo(
                                                "Says hello, in a version of the contract this code has never seen.");
                                assertThat(Json.write(each.get("inputSchema")))
                                        .as("its parameter")
                                        .contains("\"name\":{\"type\":\"string\"}");
                            });
            assertThat(tools)
                    .as("every method of the served description")
                    .hasSize(methodsDeclaredIn(served).size());
        }
    }

    // Harm: a reply carrying a value the contract added later is refused by the agent's client.
    @Test
    void aReplysEnumerationStaysOpenSoAValueAddedLaterIsStillRead() throws Exception {
        Tool list =
                Tool.of(VarlinkIdl.parse(StandInDaemon.tasks1())).stream()
                        .filter(tool -> tool.name().equals("List"))
                        .findFirst()
                        .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> activity =
                (Map<String, Object>)
                        ((Map<?, ?>) list.outputSchema().get("$defs")).get("Activity");

        assertThat(activity)
                .as("Activity in a reply names its values as examples, never as a closed enum")
                .containsKey("examples")
                .doesNotContainKey("enum");
    }

    static List<String> methodsDeclaredIn(String description) {
        Matcher matcher = Pattern.compile("(?m)^method\\s+(\\w+)").matcher(description);
        return matcher.results().map(found -> found.group(1)).toList();
    }

    static String request(Object id, String method, Map<String, Object> params) {
        return Json.write(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
    }

    static Object result(String answer) {
        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) Json.parseDecimals(answer);
        assertThat(response)
                .as("an answer with a result, not an error: %s", answer)
                .containsKey("result");
        return response.get("result");
    }
}
