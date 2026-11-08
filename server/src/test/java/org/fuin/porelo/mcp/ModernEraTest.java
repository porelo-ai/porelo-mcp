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
                .as("the modern version and the first era's, newest first")
                .containsExactly(MODERN, "2025-11-25", "2025-06-18", "2025-03-26");
        assertThat((Map<String, Object>) result.get("capabilities"))
                .as("tools")
                .containsKey("tools");
        assertThat(result.get("ttlMs"))
                .as("how long it may be kept")
                .isInstanceOf(BigDecimal.class);
        assertThat(result.get("cacheScope")).as("whose it is").isEqualTo("private");
        assertThat(serverInfo(result))
                .as("who answers")
                .isEqualTo(Map.of("name", "porelo-mcp", "version", "1.2.3"));
    }

    // Harm: a modern client refusing every list and call for the field it must find on each.
    @Test
    void toolsListAndToolsCallCarryResultTypeAndServerInfo() {
        Map<String, Object> listed = result(answer("tools/list", Map.of()));
        Map<String, Object> called =
                result(answer("tools/call", Map.of("name", "List", "arguments", Map.of())));

        assertThat(listed.get("resultType")).as("the list complete").isEqualTo("complete");
        assertThat((List<Object>) listed.get("tools")).as("the tools").isNotEmpty();
        assertThat((BigDecimal) listed.get("ttlMs")).as("how long the list holds").isPositive();
        assertThat(listed.get("cacheScope")).as("one person's list").isEqualTo("private");
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
                .as("every version it speaks, so a client of both eras finds the first one")
                .containsExactly(MODERN, "2025-11-25", "2025-06-18", "2025-03-26");
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

    // Harm: a modern client given a first-era answer, without resultType, for a request that
    // forgot only its version.
    @Test
    void aRequestOfTheSecondEraWithoutItsVersionIsRefusedNotTakenForTheFirst() {
        Map<String, Object> capabilitiesOnly =
                error(
                        handle(
                                "tools/list",
                                Map.of("_meta", Map.of(McpSession.CLIENT_CAPABILITIES, Map.of()))));

        assertThat(capabilitiesOnly.get("code"))
                .as("capabilities without a version")
                .isEqualTo(new BigDecimal(-32602));
    }

    // Harm: a modern client misreading a refused or a streamed call for want of resultType.
    @Test
    void aRefusedCallAndAStreamedCallAreCompleteResultsToo() throws Exception {
        McpSession refusing =
                new McpSession(
                        Tool.of(VarlinkIdl.parse(StandInDaemon.tasks1())),
                        (method, parameters) -> {
                            throw new org.fuin.porelo.wire.varlink.VarlinkException(
                                    "org.fuin.porelo.Tasks1.NoSuchTask", Map.of());
                        },
                        "1.2.3");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(McpSession.PROTOCOL_VERSION, MODERN);
        meta.put(McpSession.CLIENT_CAPABILITIES, Map.of());
        Map<String, Object> refused =
                result(
                        refusing.handle(
                                ContractToolsTest.request(
                                        1,
                                        "tools/call",
                                        Map.of(
                                                "name",
                                                "List",
                                                "arguments",
                                                Map.of(),
                                                "_meta",
                                                meta))));
        java.util.concurrent.BlockingQueue<String> sent =
                new java.util.concurrent.LinkedBlockingQueue<>();
        Map<String, Object> streamedMeta = new LinkedHashMap<>(meta);
        streamedMeta.put("progressToken", "p");
        try (Streams streams = new Streams(json -> sent.add(json))) {
            session.handle(
                    ContractToolsTest.request(
                            2,
                            "tools/call",
                            Map.of("name", "List", "arguments", Map.of(), "_meta", streamedMeta)),
                    streams);
            String last = null;
            for (int i = 0; i < 2; i++) {
                last = sent.poll(10, java.util.concurrent.TimeUnit.SECONDS);
            }
            assertThat(last).as("a progress, then the answer").isNotNull();
            Map<String, Object> streamed = result(last);

            assertThat(refused.get("isError")).as("refused").isEqualTo(true);
            assertThat(refused.get("resultType")).as("a refusal complete").isEqualTo("complete");
            assertThat(serverInfo(refused))
                    .as("a refusal says who")
                    .containsEntry("name", "porelo-mcp");
            assertThat(streamed.get("resultType"))
                    .as("a stream's end complete")
                    .isEqualTo("complete");
            assertThat(serverInfo(streamed))
                    .as("a stream's end says who")
                    .containsEntry("name", "porelo-mcp");
        }
    }

    // Harm: a notification of a version this server does not speak cancelling a call all the same.
    @Test
    void aCancelOfAVersionNotSpokenCancelsNothing() throws Exception {
        java.util.concurrent.CountDownLatch gone = new java.util.concurrent.CountDownLatch(1);
        Daemon waiting =
                new Daemon() {
                    @Override
                    public Map<String, Object> call(String method, Map<String, Object> parameters) {
                        return Map.of();
                    }

                    @Override
                    public Map<String, Object> stream(
                            String method,
                            Map<String, Object> parameters,
                            java.util.function.Consumer<Map<String, Object>> each,
                            Cancellation cancellation) {
                        cancellation.onCancel(gone::countDown);
                        try {
                            gone.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                        return Map.of();
                    }
                };
        McpSession watching =
                new McpSession(Tool.of(VarlinkIdl.parse(StandInDaemon.tasks1())), waiting, "1.2.3");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(McpSession.PROTOCOL_VERSION, MODERN);
        meta.put(McpSession.CLIENT_CAPABILITIES, Map.of());
        meta.put("progressToken", "w");
        try (Streams streams = new Streams(json -> true)) {
            watching.handle(
                    ContractToolsTest.request(
                            5,
                            "tools/call",
                            Map.of("name", "List", "arguments", Map.of(), "_meta", meta)),
                    streams);
            watching.handle(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":"
                            + "{\"requestId\":5,\"_meta\":{\""
                            + McpSession.PROTOCOL_VERSION
                            + "\":\"2099-01-01\"}}}",
                    streams);
            boolean cancelledByTheUnknown =
                    gone.await(300, java.util.concurrent.TimeUnit.MILLISECONDS);
            watching.handle(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":"
                            + "{\"requestId\":5}}",
                    streams);

            assertThat(cancelledByTheUnknown).as("not by a version not spoken").isFalse();
            assertThat(gone.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    .as("by a cancel it understands")
                    .isTrue();
        }
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
