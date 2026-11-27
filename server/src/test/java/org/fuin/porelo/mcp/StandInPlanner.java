package org.fuin.porelo.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.wire.Json;

/**
 * A planner for the tests, on a loopback port: it proves its token, checks each request's signature
 * as the planner's CallSignature does, answers 401 to one that does not hold, and signs its
 * answers, each part of which a test may break.
 */
final class StandInPlanner implements AutoCloseable {

    private static final Pattern SIGNED =
            Pattern.compile("porelo-planner-request-1;t=([0-9]+);n=([A-Za-z0-9_-]{22});s=.*");

    private final HttpServer server;

    private final String token;

    /** Every request that reached it, the proofs too: method, target, headers and body. */
    final List<String> seen = Collections.synchronizedList(new ArrayList<>());

    /** Whether it answers a proof with the right one. */
    volatile boolean proves = true;

    /** Whether it signs its answers under its token. */
    volatile boolean signsAnswers = true;

    /** Whether it answers every signed request 401. */
    volatile boolean refusesAll;

    /** Whether it sends the head of its answer to a signed request and then stops sending. */
    volatile boolean stalls;

    /** The status and body it answers a signed request with. */
    volatile int status = 200;

    /** The body it answers a signed request with; the request's own body when {@code null}. */
    volatile String body = "{\"ok\":true}";

    /** The description it answers {@code GET /openapi} with, or {@code null} to answer as any. */
    volatile String openApi;

    StandInPlanner(String token) throws IOException {
        this.token = token;
        this.server =
                HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        // A stalled answer holds its own thread, never the server's dispatcher.
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    PlannerAddress address() {
        return new PlannerAddress(port(), token);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            final byte[] received = exchange.getRequestBody().readAllBytes();
            final String target =
                    exchange.getRequestURI().getRawPath()
                            + (exchange.getRequestURI().getRawQuery() == null
                                    ? ""
                                    : "?" + exchange.getRequestURI().getRawQuery());
            seen.add(
                    exchange.getRequestMethod()
                            + " "
                            + target
                            + " "
                            + exchange.getRequestHeaders()
                            + " "
                            + new String(received, StandardCharsets.UTF_8));
            if (target.equals("/proof")) {
                proof(exchange, received);
                return;
            }
            final String header = exchange.getRequestHeaders().getFirst(PlannerSignature.HEADER);
            final Matcher signed = header == null ? null : SIGNED.matcher(header);
            if (refusesAll || signed == null || !signed.matches()) {
                answer(exchange, null, 401, new byte[0]);
                return;
            }
            final byte[] nonce = Base64.getUrlDecoder().decode(signed.group(2));
            final String type = exchange.getRequestHeaders().getFirst("Content-Type");
            final String expected =
                    PlannerSignature.request(
                            token,
                            exchange.getRequestMethod(),
                            target,
                            exchange.getRequestHeaders().getFirst("Host"),
                            type == null ? "" : type,
                            received,
                            Long.parseLong(signed.group(1)),
                            nonce);
            if (!expected.equals(header)) {
                answer(exchange, null, 401, new byte[0]);
                return;
            }
            if (stalls) {
                exchange.sendResponseHeaders(200, 100);
                exchange.getResponseBody().write(new byte[10]);
                exchange.getResponseBody().flush();
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            final String described = openApi;
            if (described != null && target.equals("/openapi")) {
                answer(exchange, nonce, 200, described.getBytes(StandardCharsets.UTF_8));
                return;
            }
            final String answered =
                    body == null ? new String(received, StandardCharsets.UTF_8) : body;
            answer(exchange, nonce, status, answered.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void proof(HttpExchange exchange, byte[] received) throws IOException {
        final Object json = Json.parse(new String(received, StandardCharsets.UTF_8));
        final String challenge = (String) ((Map<?, ?>) json).get("challenge");
        final String proof =
                proves
                        ? PlannerSignature.proof(
                                token, port(), Base64.getUrlDecoder().decode(challenge))
                        : PlannerSignature.proof(
                                token + "x", port(), Base64.getUrlDecoder().decode(challenge));
        final byte[] bytes = Json.write(Map.of("proof", proof)).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private void answer(HttpExchange exchange, byte[] nonce, int code, byte[] bytes)
            throws IOException {
        if (nonce != null) {
            exchange.getResponseHeaders()
                    .set(
                            PlannerSignature.ANSWER_HEADER,
                            PlannerSignature.answer(
                                    signsAnswers ? token : token + "x", nonce, code, bytes));
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
    }
}
