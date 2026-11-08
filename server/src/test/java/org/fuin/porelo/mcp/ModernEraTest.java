package org.fuin.porelo.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;

/**
 * MCP {@code 2026-07-28} beside the first era: no handshake, the version and the client's
 * capabilities in every request's {@code _meta}, {@code server/discover}, and a {@code resultType}
 * on every result.
 */
@SuppressWarnings("unchecked")
class ModernEraTest {

    private static final String MODERN = "2026-07-28";

    private McpSession session;

    @org.junit.jupiter.api.BeforeEach
    void start() throws java.io.IOException {
        session =
                new McpSession(
                        Tool.of(VarlinkIdl.parse(StandInDaemon.tasks1())),
                        (method, parameters) -> Map.of("tasks", List.of()),
                        "1.2.3");
    }

    // Harm: a client that offers only 2026-07-28 finds no server, and falls back to nothing.
    @Test
    void serverDiscoverNamesBothErasTheToolsCapabilityAndWhoAnswers() {
        Map<String, Object> result = result(answer("server/discover", Map.of()));

        assertThat(result.get("resultType")).as("a complete result").isEqualTo("complete");
        assertThat((List<Object>) result.get("supportedVersions"))
                .as("the modern version and the first era's")
                .contains(MODERN, "2025-11-25");
        assertThat((Map<String, Object>) result.get("capabilities"))
                .as("tools")
                .containsKey("tools");
        assertThat(result.get("ttlMs"))
                .as("how long it may be kept")
                .isInstanceOf(BigDecimal.class);
        assertThat(result.get("cacheScope")).as("whose it is").isEqualTo("private");
        assertThat(serverInfo(result)).as("who answers").containsEntry("name", "porelo-mcp");
    }

    // Harm: a modern client refusing every list and call for the field it must find on each.
    @Test
    void toolsListAndToolsCallCarryResultTypeAndServerInfo() {
        Map<String, Object> listed = result(answer("tools/list", Map.of()));
        Map<String, Object> called =
                result(answer("tools/call", Map.of("name", "List", "arguments", Map.of())));

        assertThat(listed.get("resultType")).as("the list complete").isEqualTo("complete");
        assertThat((List<Object>) listed.get("tools")).as("the tools").isNotEmpty();
        assertThat(listed).as("a list says how long it holds").containsKeys("ttlMs", "cacheScope");
        assertThat(serverInfo(listed)).as("the list says who").containsEntry("name", "porelo-mcp");
        assertThat(called.get("resultType")).as("the call complete").isEqualTo("complete");
        assertThat(called.get("structuredContent"))
                .as("the daemon's reply")
                .isEqualTo(Map.of("tasks", List.of()));
        assertThat(serverInfo(called)).as("the call says who").containsEntry("name", "porelo-mcp");
    }

    // Harm: a client of a version not spoken getting an answer it misreads, never the versions
    // it could use instead.
    @Test
    void aVersionNotSpokenIsRefusedNamingTheVersionsThatAre() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("io.modelcontextprotocol/protocolVersion", "2099-01-01");
        meta.put("io.modelcontextprotocol/clientCapabilities", Map.of());

        Map<String, Object> error = error(handle("server/discover", Map.of("_meta", meta)));

        assertThat(error.get("code"))
                .as("UnsupportedProtocolVersion")
                .isEqualTo(new BigDecimal(-32022));
        Map<?, ?> data = (Map<?, ?>) error.get("data");
        assertThat((List<Object>) data.get("supported"))
                .as("the versions it speaks")
                .contains(MODERN);
        assertThat(data.get("requested")).as("what was asked").isEqualTo("2099-01-01");
    }

    // Harm: a request without the client's capabilities served as if it had declared some.
    @Test
    void aModernRequestWithoutClientCapabilitiesIsInvalidParams() {
        Map<String, Object> meta = Map.of("io.modelcontextprotocol/protocolVersion", MODERN);

        Map<String, Object> error = error(handle("tools/list", Map.of("_meta", meta)));

        assertThat(error.get("code")).as("Invalid params").isEqualTo(new BigDecimal(-32602));
    }

    // Harm: a modern request answered by a method its era removed, so client and server disagree
    // on what was said.
    @Test
    void theFirstErasMethodsAreNoneOfTheModernOnes() {
        Map<String, Object> ping = error(answer("ping", Map.of()));
        Map<String, Object> initialize = error(answer("initialize", Map.of()));

        assertThat(ping.get("code")).as("ping removed").isEqualTo(new BigDecimal(-32601));
        assertThat(initialize.get("code"))
                .as("initialize removed")
                .isEqualTo(new BigDecimal(-32601));
    }

    private String answer(String method, Map<String, Object> params) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("io.modelcontextprotocol/protocolVersion", MODERN);
        meta.put("io.modelcontextprotocol/clientCapabilities", Map.of());
        Map<String, Object> withMeta = new LinkedHashMap<>(params);
        withMeta.put("_meta", meta);
        return handle(method, withMeta);
    }

    private String handle(String method, Map<String, Object> params) {
        String answered = session.handle(ContractToolsTest.request("r-1", method, params));
        assertThat(answered).as("an answer to %s", method).isNotNull();
        return answered;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(String answer) {
        Map<String, Object> message = (Map<String, Object>) Json.parseDecimals(answer);
        assertThat(message).as("a result: %s", answer).containsKey("result");
        return (Map<String, Object>) message.get("result");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> error(String answer) {
        Map<String, Object> message = (Map<String, Object>) Json.parseDecimals(answer);
        assertThat(message).as("an error: %s", answer).containsKey("error");
        return (Map<String, Object>) message.get("error");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> serverInfo(Map<String, Object> result) {
        Map<?, ?> meta = (Map<?, ?>) result.get("_meta");
        assertThat(meta).as("a result's _meta").isNotNull();
        return (Map<String, Object>) meta.get("io.modelcontextprotocol/serverInfo");
    }
}
