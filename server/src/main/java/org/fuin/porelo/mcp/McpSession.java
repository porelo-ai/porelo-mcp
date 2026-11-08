package org.fuin.porelo.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.JsonException;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.jspecify.annotations.Nullable;

/**
 * Answers MCP's JSON-RPC messages with the daemon's tools, one message at a time, whatever carries
 * them.
 *
 * <p>Every number is read exactly, so a request's {@code id} goes back and an {@code int} argument
 * goes on to the daemon as it came.
 */
public final class McpSession {

    /** The MCP versions answered, newest first; a client offering another gets the newest. */
    static final List<String> VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26");

    /** JSON-RPC's code for a message that is not JSON. */
    static final int PARSE_ERROR = -32700;

    /** JSON-RPC's code for a message that is no request. */
    static final int INVALID_REQUEST = -32600;

    /** JSON-RPC's code for a method this server does not have. */
    static final int METHOD_NOT_FOUND = -32601;

    /** JSON-RPC's code for parameters a method cannot take, such as a name that is no tool. */
    static final int INVALID_PARAMS = -32602;

    /** JSON-RPC's code for a fault on this side: here, a daemon that could not be reached. */
    static final int INTERNAL_ERROR = -32603;

    /** 2^53: up to here every whole number is exactly a double. */
    private static final double EXACT_IN_A_DOUBLE = 9_007_199_254_740_992d;

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Daemon daemon;
    private final String version;

    /**
     * Serves the given tools.
     *
     * @param tools The tools offered, by their order.
     * @param daemon Where a call goes.
     * @param version This program's version, as {@code initialize} names it.
     */
    public McpSession(List<Tool> tools, Daemon daemon, String version) {
        for (final Tool tool : tools) {
            if (this.tools.put(tool.name(), tool) != null) {
                throw new IllegalArgumentException("Two tools are named '" + tool.name() + "'");
            }
        }
        this.daemon = daemon;
        this.version = version;
    }

    /**
     * Answers one message, every call at once and in one answer.
     *
     * @param message One JSON-RPC message.
     * @return The answer, or {@code null} for a notification or a response, which get none.
     */
    public @Nullable String handle(String message) {
        return handle(message, null);
    }

    /**
     * Answers one message; a call with a progress token runs as a stream of {@code streams}, which
     * sends its progress and its answer itself.
     *
     * @param message One JSON-RPC message.
     * @param streams Where streamed calls run, or {@code null} to answer every call at once.
     * @return The answer, or {@code null} for a notification, a response, or a call that streams.
     */
    @Nullable String handle(String message, @Nullable Streams streams) {
        final Object parsed;
        try {
            parsed = Json.parseDecimals(message);
        } catch (JsonException ex) {
            return error(null, PARSE_ERROR, "Not JSON: " + ex.getMessage());
        }
        if (!(parsed instanceof Map<?, ?> request)) {
            return error(null, INVALID_REQUEST, "A message must be one JSON object");
        }
        // Only a string or a number is an id to answer with; anything else is answered with null.
        final Object given = request.get("id");
        final @Nullable Object id =
                given instanceof String || given instanceof Number ? given : null;
        if (!(request.get("method") instanceof String method)) {
            // A response to something this server asked; it asks nothing, so it is dropped.
            return request.containsKey("result") || request.containsKey("error")
                    ? null
                    : error(id, INVALID_REQUEST, "The message names no method");
        }
        if (!"2.0".equals(request.get("jsonrpc"))) {
            return request.containsKey("id")
                    ? error(id, INVALID_REQUEST, "jsonrpc must be \"2.0\"")
                    : null;
        }
        if (!request.containsKey("id")) {
            if (streams != null
                    && "notifications/cancelled".equals(method)
                    && request.get("params") instanceof Map<?, ?> cancelled
                    && cancelled.get("requestId") instanceof Object requestId
                    && (requestId instanceof String || requestId instanceof Number)) {
                streams.cancel(requestId);
            }
            return null;
        }
        if (id == null) {
            return error(null, INVALID_REQUEST, "The id must be a string or a number");
        }
        final Object params = request.get("params");
        if (params != null && !(params instanceof Map<?, ?>)) {
            return error(id, INVALID_PARAMS, "params must be an object");
        }
        @SuppressWarnings("unchecked")
        final Map<String, Object> parameters =
                params == null ? Map.of() : (Map<String, Object>) params;
        return switch (method) {
            case "initialize" -> result(id, initialize(parameters));
            case "ping" -> result(id, Map.of());
            case "tools/list" -> result(id, Map.of("tools", listed()));
            case "tools/call" -> call(id, parameters, streams);
            default -> error(id, METHOD_NOT_FOUND, "No method '" + method + "'");
        };
    }

    private Map<String, Object> initialize(Map<String, Object> parameters) {
        final Object asked = parameters.get("protocolVersion");
        final String answered =
                asked instanceof String wanted && VERSIONS.contains(wanted)
                        ? wanted
                        : VERSIONS.getFirst();
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", answered);
        result.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        result.put("serverInfo", Map.of("name", "porelo-mcp", "version", version));
        return result;
    }

    private List<Map<String, Object>> listed() {
        return tools.values().stream().map(Tool::listed).toList();
    }

    private @Nullable String call(
            Object id, Map<String, Object> parameters, @Nullable Streams streams) {
        if (!(parameters.get("name") instanceof String name)) {
            return error(id, INVALID_PARAMS, "tools/call names no tool");
        }
        final Tool tool = tools.get(name);
        if (tool == null) {
            return error(id, INVALID_PARAMS, "No tool '" + name + "'");
        }
        final Object arguments = parameters.get("arguments");
        if (arguments != null && !(arguments instanceof Map<?, ?>)) {
            return error(id, INVALID_PARAMS, "arguments must be an object");
        }
        @SuppressWarnings("unchecked")
        final Map<String, Object> given =
                arguments == null ? Map.of() : (Map<String, Object>) arguments;
        final Object token =
                parameters.get("_meta") instanceof Map<?, ?> meta
                        ? meta.get("progressToken")
                        : null;
        if (streams != null && (token instanceof String || token instanceof Number)) {
            final boolean started =
                    streams.start(
                            id,
                            cancellation -> {
                                final String answer =
                                        stream(id, tool, given, token, streams, cancellation);
                                // A cancelled request gets no answer, as MCP says.
                                streams.send(cancellation, answer);
                            });
            return started
                    ? null
                    : error(
                            id,
                            INVALID_REQUEST,
                            "At most "
                                    + Streams.MAX_STREAMS
                                    + " streams run at once, one per request id");
        }
        final Map<String, Object> reply;
        try {
            reply = daemon.call(tool.method(), given);
        } catch (VarlinkException ex) {
            return failed(id, ex);
        }
        return result(id, toolResult(exact(reply), false));
    }

    /** A streamed call: each reply as progress, the last one also the answer. */
    private String stream(
            Object id,
            Tool tool,
            Map<String, Object> given,
            Object token,
            Streams streams,
            Cancellation cancellation) {
        final long[] count = {0};
        final Map<String, Object> last;
        try {
            last =
                    daemon.stream(
                            tool.method(),
                            given,
                            reply ->
                                    streams.send(
                                            cancellation,
                                            progress(token, ++count[0], exact(reply))),
                            cancellation);
        } catch (VarlinkException ex) {
            return failed(id, ex);
        }
        return result(id, toolResult(exact(last), false));
    }

    /** A daemon's refusal as the tool's error, or a broken connection as a protocol error. */
    private static String failed(Object id, VarlinkException ex) {
        final String refusal = ex.getErrorName();
        if (refusal == null) {
            return error(id, INTERNAL_ERROR, String.valueOf(ex.getMessage()));
        }
        final Map<String, Object> refused = new LinkedHashMap<>();
        refused.put("error", refusal);
        final Map<String, Object> refusedWith = ex.getParameters();
        refused.put("parameters", refusedWith == null ? Map.of() : exact(refusedWith));
        return result(id, toolResult(refused, true));
    }

    private static String progress(Object token, long count, Map<String, Object> reply) {
        final Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("progressToken", token);
        parameters.put("progress", count);
        parameters.put("message", Json.write(reply));
        final Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/progress");
        notification.put("params", parameters);
        return Json.write(notification);
    }

    /**
     * The daemon's numbers written as it wrote them. porelo-wire's client reads each one as a
     * {@code double}, which writes 2 as {@code 2.0} and 67108864 as {@code 6.7108864E7}; a whole
     * one within a double's exact range is the same number as a {@code long}, and is written so.
     */
    @SuppressWarnings("unchecked")
    static <T> T exact(T value) {
        return (T)
                switch (value) {
                    case Double number
                            when number == Math.rint(number)
                                    && Math.abs(number) <= EXACT_IN_A_DOUBLE ->
                            number.longValue();
                    case Map<?, ?> map -> {
                        final Map<Object, Object> copy = new LinkedHashMap<>();
                        map.forEach(
                                (key, each) -> copy.put(key, each == null ? null : exact(each)));
                        yield copy;
                    }
                    case List<?> list ->
                            list.stream().map(each -> each == null ? null : exact(each)).toList();
                    default -> value;
                };
    }

    private static Map<String, Object> toolResult(Map<String, Object> content, boolean isError) {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(Map.of("type", "text", "text", Json.write(content))));
        result.put("structuredContent", content);
        result.put("isError", isError);
        return result;
    }

    private static String result(Object id, Map<String, Object> result) {
        final Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return Json.write(response);
    }

    private static String error(@Nullable Object id, int code, String message) {
        final Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        return Json.write(response);
    }
}
