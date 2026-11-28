package org.fuin.porelo.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.fuin.porelo.mcp.OpenApiToolsTest.object;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlannerDaemonTest {

    // Harm: a call addresses another resource or changes query values.
    @Test
    void aRealPathAndQueryOperationReachesItsDeclaredTarget() throws Exception {
        FakePlanner planner = new FakePlanner();
        PlannerDaemon daemon =
                new PlannerDaemon(planner, OpenApiTools.of(Json.write(OpenApiToolsTest.fixture())));
        daemon.call("searchItems", Map.of("project", "sample", "words", List.of("one two", "a&b")));
        assertThat(planner.requests)
                .as("the real search operation's REST request")
                .containsExactly(
                        new Planner.Request(
                                "GET",
                                "/projects/sample/search?words=one%20two&words=a%26b",
                                null));
    }

    // Harm: a page cursor or a repeated filter is lost or altered, so the agent reads another page.
    @Test
    void aPagedFilteredReadSendsItsCursorAndEachFilterValue() throws Exception {
        FakePlanner planner = new FakePlanner();
        PlannerDaemon daemon =
                new PlannerDaemon(planner, OpenApiTools.of(Json.write(OpenApiToolsTest.fixture())));
        daemon.call(
                "getOutline",
                Map.of(
                        "project",
                        "sample",
                        "limit",
                        20,
                        "after",
                        "eyJyIjoiYWIifQ-_",
                        "state",
                        List.of("Review", "In Progress"),
                        "repository",
                        List.of("porelo-mcp")));
        assertThat(planner.requests)
                .as("the real outline read's REST request")
                .containsExactly(
                        new Planner.Request(
                                "GET",
                                "/projects/sample/outline?limit=20&after=eyJyIjoiYWIifQ-_"
                                        + "&state=Review&state=In%20Progress"
                                        + "&repository=porelo-mcp",
                                null));
    }

    // Harm: a path value injects a segment, query or fragment into the signed target.
    @Test
    void aPathArgumentIsEncodedAsOneUtf8Segment() {
        FakePlanner planner = new FakePlanner();
        daemon(planner)
                .call(
                        "read",
                        Map.of(
                                "id",
                                "a/b c%é~_.-",
                                "q",
                                List.of(true, new BigDecimal("1.25"), "x/y")));
        assertThat(planner.requests)
                .as("RFC 3986 encoding and repeated query arguments")
                .containsExactly(
                        new Planner.Request(
                                "POST",
                                "/things/a%2Fb%20c%25%C3%A9~_.-?q=true&q=1.25&q=x%2Fy",
                                null));
    }

    // Harm: argument iteration order changes the signed query or corrupts its keys.
    @Test
    void queryArgumentsFollowTheirDeclaredOrderAndEncodeTheirNames() {
        FakePlanner planner = new FakePlanner();
        Map<String, Object> operation = OpenApiToolsTest.operation("read");
        operation.put(
                "parameters",
                List.of(
                        OpenApiToolsTest.parameter("a b", "query", false, "string"),
                        OpenApiToolsTest.parameter("second", "query", false, "integer")));
        PlannerDaemon daemon =
                new PlannerDaemon(planner, OpenApiTools.of(OpenApiToolsTest.document(operation)));
        daemon.call("read", Map.of("second", 7, "a b", "&=+?#"));
        assertThat(planner.requests)
                .as("declared order and escaped delimiters")
                .containsExactly(
                        new Planner.Request(
                                "GET", "/example?a%20b=%26%3D%2B%3F%23&second=7", null));
    }

    // Harm: body values are filtered, rounded or confused with an absent body.
    @Test
    void theJsonBodyIsForwardedWithoutChangingItsValues() {
        FakePlanner planner = new FakePlanner();
        Object body =
                Json.parseDecimals(
                        "{\"nested\":[null,true,12345678901234567890.123456789],\"words\":\"a/b\"}");
        daemon(planner).call("read", Map.of("id", "one", "body", body));
        assertThat(planner.requests)
                .as("the planner receives exactly Json.write of the body")
                .containsExactly(new Planner.Request("POST", "/things/one", Json.write(body)));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("id", "one");
        args.put("body", null);
        daemon(planner).call("read", args);
        assertThat(planner.requests.getLast().body())
                .as("explicit JSON null is a body")
                .isEqualTo("null");
    }

    // Harm: successful JSON answers lose precision, nulls or their MCP object shape.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"answer\":12345678901234567890.123456789,\"empty\":null}",
                "[1,\"two\",null]",
                "null",
                "true",
                "\"words\"",
                "12345678901234567890.123456789"
            })
    void aSuccessfulJsonAnswerKeepsEveryValue(String body) {
        FakePlanner planner = new FakePlanner();
        planner.answer = new Planner.Answer(201, "application/json", body);
        Object parsed = Json.parseDecimals(body);
        Map<String, Object> expected = new LinkedHashMap<>();
        if (parsed instanceof Map<?, ?>) {
            expected.putAll(object(parsed));
        } else {
            expected.put("result", parsed);
        }
        assertThat(daemon(planner).call("read", Map.of("id", "one")))
                .as("objects pass through and other values are wrapped")
                .isEqualTo(expected);
    }

    // Harm: an empty or text success crashes the MCP reply writer.
    @Test
    void emptyAndNonJsonSuccessesHaveAnObjectReply() {
        FakePlanner planner = new FakePlanner();
        planner.answer = new Planner.Answer(204, "", "");
        assertThat(daemon(planner).call("read", Map.of("id", "one")))
                .as("an absent body is an empty object")
                .isEmpty();
        planner.answer = new Planner.Answer(200, "text/plain", "not JSON\n");
        assertThat(daemon(planner).call("read", Map.of("id", "one")))
                .as("text is retained verbatim")
                .containsExactlyEntriesOf(Map.of("result", "not JSON\n"));
    }

    // Harm: a planner refusal loses its name, status or diagnostic fields.
    @Test
    void aConflictKeepsTheProblemTitleStatusAndBody() {
        FakePlanner planner = new FakePlanner();
        String problem =
                "{\"title\":\"Revision changed\",\"detail\":\"Keep these words.\",\"extra\":[null,1.234567890123456789]}";
        planner.answer = new Planner.Answer(409, "application/problem+json", problem);
        assertThatThrownBy(() -> daemon(planner).call("read", Map.of("id", "one")))
                .as("the problem becomes a named tool error")
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        error -> {
                            assertThat(error.getErrorName())
                                    .as("the planner's title")
                                    .isEqualTo("Revision changed");
                            assertThat(error.getParameters())
                                    .as("status and problem without filtering")
                                    .isEqualTo(
                                            Map.of(
                                                    "status",
                                                    409,
                                                    "body",
                                                    Json.parseDecimals(problem)));
                        });
    }

    // Harm: non-problem errors are swallowed or given invented diagnostics.
    @ParameterizedTest
    @ValueSource(
            strings = {"upstream failed\n", "[]", "{\"title\":7}", "{\"title\":\" \"}", "null"})
    void anErrorWithoutAStringTitleUsesItsHttpStatus(String body) {
        FakePlanner planner = new FakePlanner();
        planner.answer = new Planner.Answer(500, "text/plain", body);
        assertThatThrownBy(() -> daemon(planner).call("read", Map.of("id", "one")))
                .as("fallback errors still carry the complete body")
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        error -> {
                            assertThat(error.getErrorName())
                                    .as("status fallback")
                                    .isEqualTo("HttpStatus500");
                            assertThat(error.getParameters())
                                    .as("the error's status")
                                    .containsEntry("status", 500);
                            Object expected =
                                    body.equals("upstream failed\n")
                                            ? body
                                            : Json.parseDecimals(body);
                            assertThat(object(error.getParameters()).get("body"))
                                    .as("JSON or text remains intact")
                                    .isEqualTo(expected);
                        });
    }

    // Harm: a lost planner is misreported as the planner refusing a valid call.
    @Test
    void unavailabilityHasNoErrorNameAndKeepsItsReasonAndCause() {
        FakePlanner planner = new FakePlanner();
        Planner.Unavailable failure =
                new Planner.Unavailable(
                        Planner.Reason.NOT_RUNNING, "no planner runs: absent", null);
        planner.failure = failure;
        assertThatThrownBy(() -> daemon(planner).call("read", Map.of("id", "one")))
                .as("transport failures become internal MCP errors")
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        error -> {
                            assertThat(error.getErrorName()).as("no tool refusal name").isNull();
                            assertThat(error)
                                    .as("reason, words and original cause")
                                    .hasMessage("NOT_RUNNING: no planner runs: absent")
                                    .hasCause(failure);
                        });
    }

    // Harm: an invalid argument still sends a request that can change planner state.
    @Test
    void missingUnknownAndUnserializableArgumentsAreRefusedBeforeSending() {
        FakePlanner planner = new FakePlanner();
        PlannerDaemon daemon = daemon(planner);
        invalid(daemon, Map.of(), "id");
        invalid(daemon, Map.of("id", "one", "unknown", true), "unknown");
        invalid(daemon, Map.of("id", List.of("one")), "id");
        invalid(daemon, Map.of("id", Map.of("key", "one")), "id");
        invalid(daemon, Map.of("id", "one", "q", Map.of("key", "one")), "q");
        invalid(daemon, Map.of("id", "one", "q", List.of(List.of("one"))), "q");
        // Harm: a path argument that moves the call to another of the planner's paths.
        invalid(daemon, Map.of("id", ".."), "id");
        invalid(daemon, Map.of("id", "."), "id");
        invalid(daemon, Map.of("id", ""), "id");
        assertThat(planner.requests).as("no refused arguments reach the planner").isEmpty();
    }

    // Harm: callers bypass x-mcp by directly dispatching an excluded operation.
    @Test
    void anExcludedOperationCannotBeDispatched() throws Exception {
        FakePlanner planner = new FakePlanner();
        PlannerDaemon daemon =
                new PlannerDaemon(planner, OpenApiTools.of(Json.write(OpenApiToolsTest.fixture())));
        assertThatThrownBy(() -> daemon.call("proveToken", Map.of()))
                .as("only offered operations can be called")
                .isInstanceOf(VarlinkException.class);
        assertThat(planner.requests).as("no request for an excluded operation").isEmpty();
    }

    private static void invalid(PlannerDaemon daemon, Map<String, Object> args, String argument) {
        assertThatThrownBy(() -> daemon.call("read", args))
                .as("argument %s cannot be sent", argument)
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        error -> {
                            assertThat(error.getErrorName())
                                    .as("a local argument refusal")
                                    .isEqualTo("InvalidArgument");
                            assertThat(error.getParameters())
                                    .as("the offending argument")
                                    .containsEntry("argument", argument)
                                    .containsKey("why");
                        });
    }

    private static PlannerDaemon daemon(FakePlanner planner) {
        Map<String, Object> operation = OpenApiToolsTest.operation("read");
        operation.put(
                "parameters",
                List.of(
                        OpenApiToolsTest.parameter("id", "path", true, "string"),
                        OpenApiToolsTest.parameter("q", "query", false, "array")));
        operation.put(
                "requestBody",
                Map.of("content", Map.of("application/json", Map.of("schema", Map.of()))));
        String json =
                Json.write(
                        Map.of(
                                "openapi",
                                "3.1.0",
                                "paths",
                                Map.of("/things/{id}", Map.of("post", operation))));
        return new PlannerDaemon(planner, OpenApiTools.of(json));
    }

    private static final class FakePlanner implements Planner {
        private final List<Request> requests = new ArrayList<>();
        private Answer answer = new Answer(200, "application/json", "{}");
        private @Nullable Unavailable failure;

        @Override
        public String openApi() {
            throw new UnsupportedOperationException("The description is supplied separately");
        }

        @Override
        public Answer call(Request request) {
            requests.add(request);
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }
}
