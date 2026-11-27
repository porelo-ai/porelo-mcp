package org.fuin.porelo.mcp;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.JsonException;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.jspecify.annotations.Nullable;

/** Forwards tools described by the planner to their REST operations. */
final class PlannerDaemon implements Daemon {

    private static final String HEX = "0123456789ABCDEF";
    private final Planner planner;
    private final Map<String, OpenApiTools.Operation> operations = new LinkedHashMap<>();

    PlannerDaemon(Planner planner, OpenApiTools.Description description) {
        this.planner = planner;
        for (final OpenApiTools.Operation operation : description.operations()) {
            if (operations.put(operation.id(), operation) != null) {
                throw new IllegalArgumentException("Duplicate operationId: " + operation.id());
            }
        }
    }

    /**
     * Calls the operation under its operationId, preserving the planner's answer or refusal.
     *
     * @param method The operationId.
     * @param parameters The MCP arguments.
     * @return An object reply, wrapping non-object bodies under {@code result}.
     */
    @Override
    public Map<String, Object> call(String method, Map<String, Object> parameters) {
        final OpenApiTools.Operation operation = operations.get(method);
        if (operation == null) {
            throw invalid("method", "Unknown operationId: " + method);
        }
        final Planner.Request request = request(operation, parameters);
        final Planner.Answer answer;
        try {
            answer = planner.call(request);
        } catch (Planner.Unavailable ex) {
            throw new VarlinkException(ex.reason() + ": " + ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw invalid("path", String.valueOf(ex.getMessage()));
        }
        final @Nullable Object body = parsed(answer.body());
        if (answer.status() < 200 || answer.status() >= 300) {
            final String name =
                    body instanceof Map<?, ?> problem
                                    && problem.get("title") instanceof String title
                                    && !title.isBlank()
                            ? title
                            : "HttpStatus" + answer.status();
            final Map<String, Object> refused = new LinkedHashMap<>();
            refused.put("status", answer.status());
            refused.put("body", body);
            throw new VarlinkException(name, refused);
        }
        if (answer.body().isEmpty()) {
            return Map.of();
        }
        if (body instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> fields = (Map<String, Object>) body;
            return fields;
        }
        final Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("result", body);
        return wrapped;
    }

    private static Planner.Request request(
            OpenApiTools.Operation operation, Map<String, Object> arguments) {
        for (final String name : arguments.keySet()) {
            if (!operation.pathArguments().contains(name)
                    && !operation.queryArguments().contains(name)
                    && !(operation.body() && name.equals("body"))) {
                throw invalid(name, "Unknown argument");
            }
        }
        String path = operation.path();
        for (final String name : operation.pathArguments()) {
            if (!arguments.containsKey(name)) {
                throw invalid(name, "Missing path argument");
            }
            final String segment = scalar(name, arguments.get(name));
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                // Such a segment would send the call to another path than the operation's.
                throw invalid(name, "A path argument is empty, '.' or '..'");
            }
            path = path.replace("{" + name + "}", encode(segment));
        }
        final StringJoiner query = new StringJoiner("&");
        for (final String name : operation.queryArguments()) {
            if (!arguments.containsKey(name)) {
                continue;
            }
            final Object value = arguments.get(name);
            if (value instanceof List<?> values) {
                for (final Object each : values) {
                    query.add(encode(name) + "=" + encode(scalar(name, each)));
                }
            } else {
                query.add(encode(name) + "=" + encode(scalar(name, value)));
            }
        }
        final String target = path + (query.length() == 0 ? "" : "?" + query);
        return new Planner.Request(
                operation.method(),
                target,
                operation.body() && arguments.containsKey("body")
                        ? Json.write(arguments.get("body"))
                        : null);
    }

    private static String scalar(String name, @Nullable Object value) {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Boolean || value instanceof Number) {
            try {
                return Json.write(value);
            } catch (IllegalArgumentException ex) {
                throw invalid(name, "Value cannot be encoded as JSON: " + ex.getMessage());
            }
        }
        throw invalid(name, "Expected a string, boolean or number");
    }

    private static String encode(String value) {
        final StringBuilder encoded = new StringBuilder();
        for (final byte octet : value.getBytes(StandardCharsets.UTF_8)) {
            final int ch = Byte.toUnsignedInt(octet);
            if ((ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '-'
                    || ch == '.'
                    || ch == '_'
                    || ch == '~') {
                encoded.append((char) ch);
            } else {
                encoded.append('%').append(HEX.charAt(ch >>> 4)).append(HEX.charAt(ch & 15));
            }
        }
        return encoded.toString();
    }

    private static @Nullable Object parsed(String body) {
        try {
            return Json.parseDecimals(body);
        } catch (JsonException ex) {
            return body;
        }
    }

    private static VarlinkException invalid(String argument, String why) {
        return new VarlinkException("InvalidArgument", Map.of("argument", argument, "why", why));
    }
}
