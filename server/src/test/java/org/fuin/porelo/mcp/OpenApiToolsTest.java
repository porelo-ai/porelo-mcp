package org.fuin.porelo.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OpenApiToolsTest {

    // Harm: an excluded operation becomes callable, or an allowed operation disappears.
    @Test
    void theToolListContainsExactlyTheAllowedOperationsOfTheRealDescription() throws Exception {
        Map<String, Object> fixture = fixture();
        List<String> allowed = new ArrayList<>();
        for (Object path : object(fixture.get("paths")).values()) {
            for (Object value : object(path).values()) {
                if (value instanceof Map<?, ?> operation
                        && Boolean.TRUE.equals(operation.get("x-mcp"))) {
                    allowed.add((String) operation.get("operationId"));
                }
            }
        }
        assertThat(allowed).as("the real description's allowed reference set").hasSize(29);
        OpenApiTools.Description described = OpenApiTools.of(Json.write(fixture));
        McpSession session = new McpSession(described.tools(), (method, args) -> Map.of(), "0");
        Map<String, Object> reply =
                object(
                        Json.parseExact(
                                Objects.requireNonNull(
                                        session.handle(
                                                Json.write(
                                                        Map.of(
                                                                "jsonrpc",
                                                                "2.0",
                                                                "id",
                                                                1,
                                                                "method",
                                                                "tools/list"))))));
        List<?> tools = (List<?>) object(reply.get("result")).get("tools");
        assertThat(tools.stream().map(value -> object(value).get("name")))
                .as("tools/list follows x-mcp without a handwritten operation list")
                .containsExactlyElementsOf(allowed)
                .doesNotContain("proveToken");
        assertThat(described.tools())
                .as("each call uses the operationId")
                .allSatisfy(
                        tool ->
                                assertThat(tool.method())
                                        .as("the dispatch method")
                                        .isEqualTo(tool.name()));
    }

    // Harm: new planner capabilities require an adapter release before they can be called.
    @Test
    void anOperationAddedToACopyOfTheFixtureBecomesATool() throws Exception {
        Map<String, Object> fixture = fixture();
        object(fixture.get("paths")).put("/future", Map.of("get", operation("future")));
        assertThat(OpenApiTools.of(Json.write(fixture)).tools())
                .as("a future marked operation")
                .extracting(Tool::name)
                .hasSize(30)
                .contains("future");
    }

    // Harm: an agent cannot apply a correction the window offers, cannot reach a board read's
    // later pages, or sees a schema error instead of the last page, whose cursor is null.
    @Test
    void theRealDescriptionOffersCorrectionsAndPagedReadsWhoseLastPageValidates() throws Exception {
        Map<String, Tool> tools = new LinkedHashMap<>();
        OpenApiTools.of(Json.write(fixture()))
                .tools()
                .forEach(tool -> tools.put(tool.name(), tool));
        assertThat(tools)
                .as("the corrections marked for agents")
                .containsKeys("previewCorrection", "saveCorrection");
        assertThat(object(tools.get("saveCorrection").inputSchema().get("properties")))
                .as("the confirmation, with its seen versions, travels as the body")
                .containsKeys("project", "body");
        for (String read : List.of("listStories", "getOutline", "listIssues", "searchItems")) {
            Map<String, Object> properties =
                    object(tools.get(read).inputSchema().get("properties"));
            assertThat(properties).as(read + "'s paging parameters").containsKeys("limit", "after");
            assertThat(object(properties.get("after")).get("type"))
                    .as(read + "'s cursor is an opaque string")
                    .isEqualTo("string");
        }
        Map<String, Object> outlineInput =
                object(tools.get("getOutline").inputSchema().get("properties"));
        assertThat(object(outlineInput.get("state")).get("type"))
                .as("a filter is repeatable")
                .isEqualTo("array");
        Map<String, Object> outline =
                object(
                        object(tools.get("getOutline").outputSchema().get("$defs"))
                                .get("components/schemas/Outline"));
        assertThat(object(object(outline.get("properties")).get("next")).get("type"))
                .as("the output schema admits the last page's null cursor")
                .isEqualTo(List.of("string", "null"));
    }

    // Harm: an ambiguous or unsupported contract is accepted and calls the wrong operation.
    @ParameterizedTest
    @ValueSource(
            strings = {"[]", "null", "42", "{\"openapi\":\"3.0.3\"}", "{\"openapi\":\"3.2.0\"}"})
    void anUnsupportedDocumentIsRefused(String json) {
        assertThatThrownBy(() -> OpenApiTools.of(json))
                .as("only an OpenAPI 3.1 object is supported")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OpenAPI");
    }

    // Harm: a planner's broken description ends the session instead of offering no tools.
    @Test
    void aDescriptionThatIsNoJsonIsRefusedAsSuch() {
        assertThatThrownBy(() -> OpenApiTools.of("{\"openapi\":\"3.1.0\",\"paths\":"))
                .as("truncated JSON")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not JSON");
    }

    // Harm: a long chain of references overflows the stack and ends the session.
    @Test
    void aLongReferenceChainInAnAnswerIsRefusedNotFollowedToTheEnd() {
        Map<String, Object> schemas = new LinkedHashMap<>();
        for (int i = 0; i < 5000; i++) {
            schemas.put("S" + i, Map.of("$ref", "#/components/schemas/S" + (i + 1)));
        }
        schemas.put("S5000", Map.of("type", "object"));
        Map<String, Object> operation = operation("read");
        operation.put(
                "responses", Map.of("200", response(Map.of("$ref", "#/components/schemas/S0"))));
        Map<String, Object> root = object(Json.parseExact(document(operation)));
        root.put("components", Map.of("schemas", schemas));
        assertThatThrownBy(() -> OpenApiTools.of(Json.write(root)))
                .as("a bounded refusal, not a StackOverflowError")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reference depth");
    }

    // Harm: a call sent in another serialization than the planner reads, or without its body.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"name\":\"q\",\"in\":\"query\",\"explode\":false,\"schema\":{}}",
                "{\"name\":\"q\",\"in\":\"query\",\"style\":\"pipeDelimited\",\"schema\":{}}",
                "{\"name\":\"q\",\"in\":\"query\",\"allowReserved\":true,\"schema\":{}}",
                "{\"name\":\"q\",\"in\":\"query\",\"schema\":{}},"
                        + "{\"name\":\"q\",\"in\":\"query\",\"schema\":{}}"
            })
    void aParameterSentOtherwiseThanACallSendsItIsRefused(String parameters) {
        Map<String, Object> operation = operation("read");
        operation.put("parameters", Json.parseExact("[" + parameters + "]"));
        assertThatThrownBy(() -> OpenApiTools.of(document(operation)))
                .as("parameters %s", parameters)
                .isInstanceOf(IllegalArgumentException.class);
        Map<String, Object> withBody = operation("write");
        withBody.put(
                "requestBody", Map.of("content", Map.of("text/plain", Map.of("schema", Map.of()))));
        assertThatThrownBy(() -> OpenApiTools.of(document(withBody)))
                .as("a body that is not JSON")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestBody");
    }

    // Harm: malformed visibility metadata silently exposes or hides operations.
    @ParameterizedTest
    @ValueSource(
            strings = {"false", "null", "0", "\"true\"", "\"false\"", "\"false \"", "\"false   \""})
    void anInvalidVisibilityMarkerIsRefused(String marker) {
        Map<String, Object> operation = operation("read");
        operation.put("x-mcp", Json.parseExact(marker));
        assertThatThrownBy(() -> OpenApiTools.of(document(operation)))
                .as("x-mcp needs an explicit decision")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("x-mcp");
    }

    // Harm: an operation with no decision is accidentally offered.
    @Test
    void aMissingVisibilityMarkerIsRefused() {
        Map<String, Object> operation = operation("read");
        operation.remove("x-mcp");
        assertThatThrownBy(() -> OpenApiTools.of(document(operation)))
                .as("missing x-mcp")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("x-mcp");
    }

    // Harm: two operations share a dispatch key or a key cannot be formed.
    @Test
    void missingAndDuplicateIdsAreRefusedEvenOnExcludedOperations() {
        Map<String, Object> operation = operation("read");
        operation.put("x-mcp", "false internal operation");
        operation.remove("operationId");
        assertThatThrownBy(() -> OpenApiTools.of(document(operation)))
                .as("excluded operation still needs an id")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operationId");
        operation.put("operationId", "read");
        Map<String, Object> root = object(Json.parseExact(document(operation)));
        object(root.get("paths")).put("/duplicate", Map.of("post", operation("read")));
        assertThatThrownBy(() -> OpenApiTools.of(Json.write(root)))
                .as("ids are unique across all operations")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read");
    }

    // Harm: input schemas lose inherited requirements or constrain an overridden argument wrongly.
    @Test
    void parametersAreMergedAndTheJsonBodyKeepsItsSchema() {
        Map<String, Object> operation = operation("read");
        operation.put("summary", "Summary");
        operation.put("description", "Details");
        operation.put("parameters", List.of(parameter("q", "query", true, "integer")));
        operation.put(
                "requestBody",
                Map.of(
                        "required",
                        true,
                        "content",
                        Map.of(
                                "application/json",
                                Map.of(
                                        "schema",
                                        Map.of(
                                                "type",
                                                "array",
                                                "items",
                                                Map.of("type", "string"))))));
        Map<String, Object> root = object(Json.parseExact(document(operation)));
        Map<String, Object> paths = object(root.get("paths"));
        paths.put("/example/{id}", paths.remove("/example"));
        object(paths.get("/example/{id}"))
                .put(
                        "parameters",
                        List.of(
                                parameter("id", "path", false, "string"),
                                parameter("q", "query", false, "string")));
        Tool tool = OpenApiTools.of(Json.write(root)).tools().getFirst();
        assertThat(tool.description())
                .as("both descriptions reach the client")
                .isEqualTo("Summary\n\nDetails");
        assertThat(tool.inputSchema().get("required"))
                .as("path, winning query and body are required")
                .isEqualTo(List.of("id", "q", "body"));
        Map<String, Object> properties = object(tool.inputSchema().get("properties"));
        assertThat(object(properties.get("q")))
                .as("the operation overrides the path item")
                .containsEntry("type", "integer")
                .containsEntry("description", "q words");
        assertThat(object(properties.get("body")))
                .as("the request body is one property")
                .containsEntry("type", "array");
    }

    // Harm: one MCP argument ambiguously denotes two different REST values.
    @Test
    void aBodyParameterAndRequestBodyAreRefusedAsAClash() {
        Map<String, Object> operation = operation("read");
        operation.put("parameters", List.of(parameter("body", "query", false, "string")));
        operation.put(
                "requestBody",
                Map.of("content", Map.of("application/json", Map.of("schema", Map.of()))));
        assertThatThrownBy(() -> OpenApiTools.of(document(operation)))
                .as("body has only one meaning")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("body");
    }

    // Harm: schemas refer outside their document or to missing definitions.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://example.invalid/schema",
                "other.json#/components/schemas/Thing",
                "#/components/schemas/Missing"
            })
    void anExternalOrMissingReferenceIsRefused(String ref) {
        Map<String, Object> operation = operation("read");
        operation.put(
                "parameters",
                List.of(Map.of("name", "q", "in", "query", "schema", Map.of("$ref", ref))));
        assertThatThrownBy(() -> OpenApiTools.of(document(operation)))
                .as("every reached reference stays local and exists")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ref);
    }

    // Harm: recursive schemas hang discovery, or unrelated definitions inflate every tool.
    @Test
    void reachedReferencesBecomeLocalDefinitionsAndCyclesTerminate() {
        Map<String, Object> root = object(Json.parseExact(document(operation("read"))));
        Map<String, Object> operation =
                object(object(object(root.get("paths")).get("/example")).get("get"));
        operation.put(
                "requestBody",
                Map.of(
                        "content",
                        Map.of(
                                "application/json",
                                Map.of("schema", Map.of("$ref", "#/components/schemas/Node")))));
        root.put(
                "components",
                Map.of(
                        "schemas",
                        Map.of(
                                "Node",
                                        Map.of(
                                                "type",
                                                "object",
                                                "properties",
                                                Map.of(
                                                        "next",
                                                        Map.of(
                                                                "$ref",
                                                                "#/components/schemas/Other"))),
                                "Other", Map.of("$ref", "#/components/schemas/Node"),
                                "Unused", Map.of("$ref", "https://example.invalid/unused"))));
        Map<String, Object> input =
                OpenApiTools.of(Json.write(root)).tools().getFirst().inputSchema();
        Map<String, Object> defs = object(input.get("$defs"));
        String bodyRef = (String) object(object(input.get("properties")).get("body")).get("$ref");
        assertThat(defs).as("only the two reachable definitions are copied").hasSize(2);
        assertThat(bodyRef).as("the body resolves within its own schema").startsWith("#/$defs/");
        assertThat(Json.write(input))
                .as("all recursive links are rebased")
                .doesNotContain("#/components/", "example.invalid", "Unused");
    }

    // Harm: a response schema describes another status or disagrees with the object reply shape.
    @Test
    void theLowestSuccessResponseSuppliesAnObjectSchemaOrTheFallback() {
        Map<String, Object> operation = operation("read");
        operation.put(
                "responses",
                Map.of(
                        "201",
                        response(Map.of("type", "array")),
                        "200",
                        response(Map.of("$ref", "#/components/schemas/Reply"))));
        Map<String, Object> root = object(Json.parseExact(document(operation)));
        root.put(
                "components",
                Map.of(
                        "schemas",
                        Map.of("Reply", Map.of("type", "object", "required", List.of("answer")))));
        Map<String, Object> output =
                OpenApiTools.of(Json.write(root)).tools().getFirst().outputSchema();
        assertThat(Json.write(output))
                .as("the lower status's resolved object schema")
                .contains("answer", "$defs");
        operation.put("responses", Map.of("200", response(Map.of("type", "array"))));
        assertThat(OpenApiTools.of(document(operation)).tools().getFirst().outputSchema())
                .as("Tool requires an object schema even for a wrapped reply")
                .isEqualTo(Map.of("type", "object"));
    }

    // Harm: hostile descriptions exhaust the stack or memory during discovery.
    @Test
    void oversizedAndDeeplyNestedDescriptionsAreRefusedBeforeParsing() {
        assertThatThrownBy(() -> OpenApiTools.of(" ".repeat(VarlinkIdl.MAX_LENGTH + 1)))
                .as("the description has a bounded length")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length");
        assertThatThrownBy(
                        () ->
                                OpenApiTools.of(
                                        "[".repeat(VarlinkIdl.MAX_DEPTH + 1)
                                                + "0"
                                                + "]".repeat(VarlinkIdl.MAX_DEPTH + 1)))
                .as("nesting is bounded before the JSON reader recurses")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("depth");
    }

    static Map<String, Object> fixture() throws Exception {
        try (InputStream in =
                Objects.requireNonNull(
                        OpenApiToolsTest.class.getResourceAsStream("/openapi/planner.json"))) {
            return object(Json.parseExact(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        }
    }

    static Map<String, Object> operation(String id) {
        return new LinkedHashMap<>(
                Map.of(
                        "operationId",
                        id,
                        "x-mcp",
                        true,
                        "responses",
                        Map.of("204", Map.of("description", "Empty"))));
    }

    static String document(Map<String, Object> operation) {
        return Json.write(
                Map.of("openapi", "3.1.0", "paths", Map.of("/example", Map.of("get", operation))));
    }

    static Map<String, Object> parameter(String name, String in, boolean required, String type) {
        return Map.of(
                "name",
                name,
                "in",
                in,
                "required",
                required,
                "description",
                name + " words",
                "schema",
                Map.of("type", type));
    }

    private static Map<String, Object> response(Map<String, Object> schema) {
        return Map.of("content", Map.of("application/json", Map.of("schema", schema)));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }
}
