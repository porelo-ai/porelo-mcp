package org.fuin.porelo.mcp;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.JsonException;
import org.jspecify.annotations.Nullable;

/** Reads the planner's operations and their MCP schemas from its OpenAPI description. */
final class OpenApiTools {

    static final int MAX_LENGTH = VarlinkIdl.MAX_LENGTH;
    static final int MAX_DEPTH = VarlinkIdl.MAX_DEPTH;
    private static final Set<String> METHODS =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");
    private static final Set<String> SCHEMA_MAPS =
            Set.of("$defs", "properties", "patternProperties", "dependentSchemas");
    private static final Set<String> SCHEMA_ARRAYS =
            Set.of("allOf", "anyOf", "oneOf", "prefixItems");
    private static final Set<String> SCHEMAS =
            Set.of(
                    "items",
                    "additionalProperties",
                    "unevaluatedProperties",
                    "contains",
                    "not",
                    "if",
                    "then",
                    "else",
                    "propertyNames",
                    "unevaluatedItems",
                    "contentSchema");

    private final Map<String, Object> document;

    private OpenApiTools(Map<String, Object> document) {
        this.document = document;
    }

    /** The tools and the REST operations to which their calls are forwarded. */
    record Description(List<Tool> tools, List<Operation> operations) {
        Description {
            tools = List.copyOf(tools);
            operations = List.copyOf(operations);
        }
    }

    /** The routing information of one offered operation. */
    record Operation(
            String id,
            String method,
            String path,
            List<String> pathArguments,
            List<String> queryArguments,
            boolean body) {
        Operation {
            pathArguments = List.copyOf(pathArguments);
            queryArguments = List.copyOf(queryArguments);
        }
    }

    /** Reads and bounds a description, refusing ambiguous operation names or visibility. */
    static Description of(String openApiJson) {
        bound(openApiJson);
        final Object parsed;
        try {
            parsed = Json.parseDecimals(openApiJson);
        } catch (JsonException ex) {
            throw new IllegalArgumentException(
                    "The OpenAPI description is not JSON: " + ex.getMessage(), ex);
        }
        final Map<String, Object> document = object(parsed, "OpenAPI document");
        if (!(document.get("openapi") instanceof String version)
                || !version.matches("3\\.1\\.[0-9]+")) {
            throw new IllegalArgumentException("OpenAPI version must be 3.1.x");
        }
        return new OpenApiTools(document).read();
    }

    private Description read() {
        final List<Tool> tools = new ArrayList<>();
        final List<Operation> operations = new ArrayList<>();
        final Set<String> ids = new HashSet<>();
        long size = 0;
        for (final var path : object(document.get("paths"), "OpenAPI paths").entrySet()) {
            if (path.getKey().startsWith("x-")) {
                continue;
            }
            final Map<String, Object> item = dereference(path.getValue());
            for (final var entry : item.entrySet()) {
                if (!METHODS.contains(entry.getKey())) {
                    continue;
                }
                final Map<String, Object> operation = object(entry.getValue(), "operation");
                final String id = text(operation.get("operationId"), "operationId");
                if (!ids.add(id)) {
                    throw new IllegalArgumentException("Duplicate operationId: " + id);
                }
                final Object marker = operation.get("x-mcp");
                if (marker instanceof String excluded
                        && excluded.startsWith("false ")
                        && !excluded.substring(6).isBlank()) {
                    continue;
                }
                if (!Boolean.TRUE.equals(marker)) {
                    throw new IllegalArgumentException("Invalid x-mcp for " + id);
                }
                final Tool tool =
                        tool(id, item, operation, path.getKey(), entry.getKey(), operations);
                size += Json.write(tool.listed()).length();
                if (size > MAX_LENGTH) {
                    throw new IllegalArgumentException(
                            "OpenAPI tools exceed the maximum length " + MAX_LENGTH);
                }
                tools.add(tool);
            }
        }
        return new Description(tools, operations);
    }

    private Tool tool(
            String id,
            Map<String, Object> item,
            Map<String, Object> operation,
            String path,
            String method,
            List<Operation> operations) {
        final Map<List<String>, Map<String, Object>> parameters = new LinkedHashMap<>();
        parameters(item.get("parameters"), parameters);
        parameters(operation.get("parameters"), parameters);
        final Map<String, Object> properties = new LinkedHashMap<>();
        final List<String> required = new ArrayList<>();
        final List<String> paths = new ArrayList<>();
        final List<String> queries = new ArrayList<>();
        for (final Map<String, Object> parameter : parameters.values()) {
            final String in = text(parameter.get("in"), "parameter in");
            if (!in.equals("path") && !in.equals("query")) {
                throw new IllegalArgumentException("Unsupported parameter location: " + in);
            }
            final String name = text(parameter.get("name"), "parameter name");
            Object schema = schema(parameter.get("schema"));
            styled(parameter, in, name);
            if (parameter.get("description") instanceof String description
                    && !description.isBlank()) {
                final Map<String, Object> described =
                        schema instanceof Map<?, ?>
                                ? new LinkedHashMap<>(object(schema, "parameter schema"))
                                : new LinkedHashMap<>(Map.of("allOf", List.of(schema)));
                described.put("description", description);
                schema = described;
            }
            if (properties.put(name, schema) != null) {
                throw new IllegalArgumentException("Clashing parameter name: " + name);
            }
            (in.equals("path") ? paths : queries).add(name);
            if (in.equals("path") || Boolean.TRUE.equals(parameter.get("required"))) {
                required.add(name);
            }
        }
        final Map<String, Object> body =
                operation.containsKey("requestBody")
                        ? dereference(operation.get("requestBody"))
                        : Map.of();
        final @Nullable Object bodySchema = jsonSchema(body);
        if (operation.containsKey("requestBody") && bodySchema == null) {
            throw new IllegalArgumentException("Unsupported requestBody content for " + id);
        }
        if (bodySchema != null) {
            if (properties.put("body", bodySchema) != null) {
                throw new IllegalArgumentException("Parameter body clashes with the request body");
            }
            if (Boolean.TRUE.equals(body.get("required"))) {
                required.add("body");
            }
        }
        final Map<String, Object> input = new LinkedHashMap<>();
        input.put("type", "object");
        input.put("properties", properties);
        if (!required.isEmpty()) {
            input.put("required", required);
        }
        validatePath(path, paths);
        operations.add(
                new Operation(
                        id,
                        method.toUpperCase(Locale.ROOT),
                        path,
                        paths,
                        queries,
                        bodySchema != null));
        final List<String> words = new ArrayList<>();
        for (final String field : List.of("summary", "description")) {
            if (operation.get(field) instanceof String value && !value.isBlank()) {
                words.add(value);
            }
        }
        return new Tool(
                id, id, String.join("\n\n", words), new Schema().root(input), output(operation));
    }

    private void parameters(
            @Nullable Object value, Map<List<String>, Map<String, Object>> parameters) {
        if (value == null) {
            return;
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("OpenAPI parameters must be an array");
        }
        final Set<List<String>> seen = new HashSet<>();
        for (final Object each : list) {
            final Map<String, Object> parameter = dereference(each);
            final List<String> key =
                    List.of(
                            text(parameter.get("in"), "parameter in"),
                            text(parameter.get("name"), "parameter name"));
            if (!seen.add(key)) {
                throw new IllegalArgumentException("Duplicate parameter: " + key);
            }
            parameters.put(key, parameter);
        }
    }

    /** Refuses a serialization other than the one a call sends: path simple, query form. */
    private static void styled(Map<String, Object> parameter, String in, String name) {
        final String style = in.equals("path") ? "simple" : "form";
        final boolean explode = !in.equals("path");
        if ((parameter.containsKey("style") && !style.equals(parameter.get("style")))
                || (parameter.containsKey("explode")
                        && !Boolean.valueOf(explode).equals(parameter.get("explode")))
                || Boolean.TRUE.equals(parameter.get("allowReserved"))
                || parameter.containsKey("content")) {
            throw new IllegalArgumentException("Unsupported serialization of parameter " + name);
        }
    }

    private Map<String, Object> output(Map<String, Object> operation) {
        final Map<String, Object> responses = object(operation.get("responses"), "responses");
        final @Nullable String first =
                responses.keySet().stream()
                        .filter(status -> status.matches("2[0-9]{2}"))
                        .min(String::compareTo)
                        .orElse(null);
        if (first != null) {
            final @Nullable Object schema = jsonSchema(dereference(responses.get(first)));
            if (schema != null) {
                // Even a non-object schema must have its reached references checked.
                final Map<String, Object> resolved = new Schema().root(schema);
                if (isObject(schema, new HashSet<>())) {
                    final Map<String, Object> result = new LinkedHashMap<>(resolved);
                    result.put("type", "object");
                    return Collections.unmodifiableMap(result);
                }
            }
        }
        return Map.of("type", "object");
    }

    private boolean isObject(Object schema, Set<String> visited) {
        Object current = schema;
        while (current instanceof Map<?, ?> fields) {
            if (fields.containsKey("type")) {
                // A type beside a reference narrows it; only a plain object is one.
                return "object".equals(fields.get("type"));
            }
            if (!(fields.get("$ref") instanceof String ref) || !visited.add(ref)) {
                return false;
            }
            if (visited.size() > MAX_DEPTH) {
                throw new IllegalArgumentException(
                        "Cyclic or excessive OpenAPI reference depth: " + ref);
            }
            current = reference(ref);
        }
        return false;
    }

    private static @Nullable Object jsonSchema(Map<String, Object> holder) {
        if (!holder.containsKey("content")) {
            return null;
        }
        final Map<String, Object> content = object(holder.get("content"), "content");
        if (!content.containsKey("application/json")) {
            return null;
        }
        return schema(
                object(content.get("application/json"), "application/json")
                        .getOrDefault("schema", Map.of()));
    }

    private Map<String, Object> dereference(@Nullable Object value) {
        Map<String, Object> fields = object(value, "OpenAPI object");
        final Map<String, Object> siblings = new LinkedHashMap<>();
        final Set<String> visited = new HashSet<>();
        while (fields.containsKey("$ref")) {
            final String ref = text(fields.get("$ref"), "$ref");
            if (!visited.add(ref) || visited.size() > MAX_DEPTH) {
                throw new IllegalArgumentException(
                        "Cyclic or excessive OpenAPI reference depth: " + ref);
            }
            fields.forEach(
                    (key, item) -> {
                        if (!key.equals("$ref")) {
                            siblings.putIfAbsent(key, item);
                        }
                    });
            fields = object(reference(ref), ref);
        }
        final Map<String, Object> result = new LinkedHashMap<>(fields);
        result.putAll(siblings);
        return result;
    }

    private Object reference(String ref) {
        if (!ref.startsWith("#/components/")) {
            throw new IllegalArgumentException("Non-local OpenAPI $ref: " + ref);
        }
        final String fragment;
        try {
            fragment = java.util.Objects.requireNonNull(URI.create(ref).getFragment());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid OpenAPI $ref: " + ref, ex);
        }
        Object value = document;
        for (final String encoded : fragment.substring(1).split("/", -1)) {
            if (encoded.matches(".*~(?:[^01]|$).*")) {
                throw new IllegalArgumentException("Invalid OpenAPI $ref: " + ref);
            }
            final String key = encoded.replace("~1", "/").replace("~0", "~");
            final @Nullable Object next;
            if (value instanceof Map<?, ?> fields) {
                next = fields.get(key);
            } else if (value instanceof List<?> list && key.matches("0|[1-9][0-9]*")) {
                try {
                    final int index = Integer.parseInt(key);
                    next = index < list.size() ? list.get(index) : null;
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("Missing OpenAPI $ref: " + ref, ex);
                }
            } else {
                next = null;
            }
            if (next == null) {
                throw new IllegalArgumentException("Missing OpenAPI $ref: " + ref);
            }
            value = next;
        }
        return value;
    }

    private static void validatePath(String path, List<String> arguments) {
        if (!path.startsWith("/")
                || path.startsWith("//")
                || path.contains("?")
                || path.contains("#")) {
            throw new IllegalArgumentException(
                    "OpenAPI path must be an absolute request path: " + path);
        }
        String literal = path;
        for (final String name : arguments) {
            if (!literal.contains("{" + name + "}")) {
                throw new IllegalArgumentException("Path parameter has no placeholder: " + name);
            }
            literal = literal.replace("{" + name + "}", "segment");
        }
        if (literal.contains("{") || literal.contains("}")) {
            throw new IllegalArgumentException("OpenAPI path has an undeclared parameter: " + path);
        }
        URI.create(literal);
    }

    private static Object schema(@Nullable Object value) {
        if (value instanceof Map<?, ?> || value instanceof Boolean) {
            return value;
        }
        throw new IllegalArgumentException("OpenAPI schema must be an object or boolean");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(@Nullable Object value, String name) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return (Map<String, Object>) value;
    }

    private static String text(@Nullable Object value, String name) {
        if (value instanceof String string && !string.isBlank()) {
            return string;
        }
        throw new IllegalArgumentException("Missing or blank " + name);
    }

    private static void bound(String json) {
        if (json.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "OpenAPI description exceeds the maximum length " + MAX_LENGTH);
        }
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int at = 0; at < json.length(); at++) {
            final char ch = json.charAt(at);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    quoted = false;
                }
            } else if (ch == '"') {
                quoted = true;
            } else if ((ch == '{' || ch == '[') && ++depth > MAX_DEPTH) {
                throw new IllegalArgumentException(
                        "OpenAPI description exceeds the maximum depth " + MAX_DEPTH);
            } else if (ch == '}' || ch == ']') {
                depth--;
            }
        }
    }

    /** Each root owns only the definitions reached from that schema. */
    private final class Schema {
        private final Map<String, String> names = new LinkedHashMap<>();
        private final List<String> pending = new ArrayList<>();
        private int nodes;

        Map<String, Object> root(Object value) {
            final Map<String, Object> root =
                    value instanceof Boolean
                            ? new LinkedHashMap<>(Map.of("allOf", List.of(value)))
                            : new LinkedHashMap<>(object(copy(value, 0), "schema"));
            final Map<String, Object> defs =
                    root.containsKey("$defs")
                            ? new LinkedHashMap<>(object(root.get("$defs"), "$defs"))
                            : new LinkedHashMap<>();
            for (int done = 0; done < pending.size(); done++) {
                final String ref = pending.get(done);
                defs.put(names.get(ref), copy(schema(reference(ref)), 0));
            }
            if (!defs.isEmpty()) {
                root.put("$defs", Collections.unmodifiableMap(defs));
            }
            if (Json.write(root).length() > MAX_LENGTH) {
                throw new IllegalArgumentException(
                        "OpenAPI schema exceeds the maximum length " + MAX_LENGTH);
            }
            return Collections.unmodifiableMap(root);
        }

        private Object copy(Object value, int depth) {
            if (depth > MAX_DEPTH || ++nodes > MAX_LENGTH) {
                throw new IllegalArgumentException("OpenAPI schema exceeds depth or size bounds");
            }
            if (value instanceof Boolean) {
                return value;
            }
            final Map<String, Object> fields = object(value, "schema");
            final Map<String, Object> copied = new LinkedHashMap<>();
            for (final var field : fields.entrySet()) {
                final String key = field.getKey();
                final Object item = field.getValue();
                if (key.equals("$ref")) {
                    final String ref = text(item, "$ref");
                    if (!names.containsKey(ref)) {
                        schema(reference(ref));
                        // A pointer-derived key cannot collide with a different component pointer.
                        names.put(ref, ref.substring(2));
                        pending.add(ref);
                    }
                    copied.put(
                            key, "#/$defs/" + names.get(ref).replace("~", "~0").replace("/", "~1"));
                } else if (SCHEMA_MAPS.contains(key)) {
                    final Map<String, Object> children = new LinkedHashMap<>();
                    object(item, key)
                            .forEach(
                                    (name, child) ->
                                            children.put(name, copy(schema(child), depth + 1)));
                    copied.put(key, Collections.unmodifiableMap(children));
                } else if (SCHEMA_ARRAYS.contains(key)) {
                    if (!(item instanceof List<?> children)) {
                        throw new IllegalArgumentException("Schema " + key + " must be an array");
                    }
                    copied.put(
                            key,
                            children.stream()
                                    .map(child -> copy(schema(child), depth + 1))
                                    .toList());
                } else if (SCHEMAS.contains(key)) {
                    copied.put(key, copy(schema(item), depth + 1));
                } else if (key.equals("$id") || key.equals("$dynamicRef")) {
                    throw new IllegalArgumentException(
                            "Unsupported schema rebasing keyword: " + key);
                } else {
                    copied.put(key, item);
                }
            }
            return Collections.unmodifiableMap(copied);
        }
    }
}
