package org.fuin.porelo.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One method of the daemon's interface, offered as an MCP tool.
 *
 * @param name The tool's name: the method's name without its interface.
 * @param method The method's fully qualified name, as the daemon is called with it.
 * @param description The method's comment.
 * @param inputSchema The JSON Schema of its parameters.
 * @param outputSchema The JSON Schema of its reply.
 */
public record Tool(
        String name,
        String method,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema) {

    /** Copies the schemas, so a tool cannot change once listed. */
    public Tool {
        inputSchema = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(inputSchema));
        outputSchema = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(outputSchema));
    }

    /**
     * Every method of an interface as a tool, in the order declared.
     *
     * @param described The interface.
     * @return Its tools.
     */
    public static List<Tool> of(VarlinkInterface described) {
        final List<Tool> tools = new ArrayList<>();
        for (final VarlinkInterface.Method method : described.methods()) {
            tools.add(
                    new Tool(
                            method.name(),
                            described.name() + "." + method.name(),
                            method.description(),
                            new Schema(described, true).root(method.input()),
                            new Schema(described, false).root(method.output())));
        }
        return List.copyOf(tools);
    }

    /** The MCP form of this tool, as {@code tools/list} lists it. */
    Map<String, Object> listed() {
        final Map<String, Object> listed = new LinkedHashMap<>();
        listed.put("name", name);
        if (!description.isEmpty()) {
            listed.put("description", description);
        }
        listed.put("inputSchema", inputSchema);
        listed.put("outputSchema", outputSchema);
        return listed;
    }

    /**
     * Writes one schema with the named types it reaches as {@code $defs}.
     *
     * <p>An enumeration is closed in what the agent sends and open in what the daemon answers,
     * since the contract adds values to a reply's enumeration without notice and a client must
     * render one it does not know.
     */
    private static final class Schema {

        private final VarlinkInterface described;
        private final boolean input;
        private final Set<String> reached = new LinkedHashSet<>();
        private final List<String> order = new ArrayList<>();

        Schema(VarlinkInterface described, boolean input) {
            this.described = described;
            this.input = input;
        }

        Map<String, Object> root(VarlinkType.Struct struct) {
            final Map<String, Object> root = new LinkedHashMap<>(of(struct));
            final Map<String, Object> defs = new LinkedHashMap<>();
            // Each definition may reach further names, so they are written until none is new.
            for (int done = 0; done < order.size(); done++) {
                final String name = order.get(done);
                final VarlinkInterface.Declared declared = described.types().get(name);
                if (declared == null) {
                    throw new IllegalStateException("The type '" + name + "' is not declared");
                }
                final Map<String, Object> def = new LinkedHashMap<>(of(declared.type()));
                if (!declared.description().isEmpty()) {
                    // A declared enumeration's values keep their words after the type's.
                    def.merge(
                            "description",
                            declared.description(),
                            (values, own) -> own + "\n" + values);
                }
                defs.put(name, def);
            }
            if (!defs.isEmpty()) {
                root.put("$defs", defs);
            }
            return root;
        }

        private Map<String, Object> of(VarlinkType type) {
            return switch (type) {
                case VarlinkType.Builtin builtin ->
                        switch (builtin) {
                            case BOOL -> Map.of("type", "boolean");
                            case INT -> Map.of("type", "integer");
                            case FLOAT -> Map.of("type", "number");
                            case STRING -> Map.of("type", "string");
                            case OBJECT -> Map.of("type", "object");
                        };
                case VarlinkType.Named named -> {
                    if (reached.add(named.name())) {
                        order.add(named.name());
                    }
                    yield Map.of("$ref", "#/$defs/" + named.name());
                }
                // Absent is the only thing a ?type adds, and leaving it out of 'required' says
                // that.
                case VarlinkType.Maybe maybe -> of(maybe.inner());
                case VarlinkType.Array array ->
                        Map.of("type", "array", "items", of(array.element()));
                case VarlinkType.Dict dict ->
                        Map.of("type", "object", "additionalProperties", of(dict.value()));
                case VarlinkType.Struct struct -> struct(struct);
                case VarlinkType.Enum enumeration -> enumeration(enumeration);
            };
        }

        private Map<String, Object> struct(VarlinkType.Struct struct) {
            final Map<String, Object> properties = new LinkedHashMap<>();
            final List<String> required = new ArrayList<>();
            for (final VarlinkType.Field field : struct.fields()) {
                final Map<String, Object> property = new LinkedHashMap<>(of(field.type()));
                if (!field.description().isEmpty()) {
                    // An inline enumeration's own values keep their words after the field's.
                    property.merge(
                            "description",
                            field.description(),
                            (values, own) -> own + "\n" + values);
                }
                properties.put(field.name(), property);
                if (!(field.type() instanceof VarlinkType.Maybe)) {
                    required.add(field.name());
                }
            }
            final Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            if (!required.isEmpty()) {
                schema.put("required", required);
            }
            return schema;
        }

        private Map<String, Object> enumeration(VarlinkType.Enum enumeration) {
            final List<String> names =
                    enumeration.values().stream().map(VarlinkType.Member::name).toList();
            final Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "string");
            if (input) {
                schema.put("enum", names);
            } else {
                schema.put("examples", names);
            }
            final StringBuilder described = new StringBuilder();
            for (final VarlinkType.Member member : enumeration.values()) {
                if (!member.description().isEmpty()) {
                    described
                            .append(described.isEmpty() ? "" : "\n")
                            .append(member.name())
                            .append(": ")
                            .append(member.description());
                }
            }
            if (!described.isEmpty()) {
                schema.put("description", described.toString());
            }
            return schema;
        }
    }
}
