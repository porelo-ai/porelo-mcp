package org.fuin.porelo.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed Varlink interface description.
 *
 * @param name The interface's fully qualified name, such as {@code org.fuin.porelo.Tasks1}.
 * @param types Its named types, by name, in the order declared.
 * @param methods Its methods, in the order declared.
 * @param errors Its errors, in the order declared.
 */
public record VarlinkInterface(
        String name, Map<String, Declared> types, List<Method> methods, List<ErrorType> errors) {

    /** Copies the collections, keeping their order, so the interface cannot change. */
    public VarlinkInterface {
        types = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(types));
        methods = List.copyOf(methods);
        errors = List.copyOf(errors);
    }

    /**
     * A named type.
     *
     * @param name Its name.
     * @param description The comment written before it, or {@code ""}.
     * @param type What it is: a struct or an enumeration.
     */
    public record Declared(String name, String description, VarlinkType type) {}

    /**
     * A method.
     *
     * @param name Its name, without the interface.
     * @param description The comment written before it, or {@code ""}.
     * @param input Its parameters.
     * @param output Its reply.
     */
    public record Method(
            String name, String description, VarlinkType.Struct input, VarlinkType.Struct output) {}

    /**
     * An error.
     *
     * @param name Its name, without the interface.
     * @param description The comment written before it, or {@code ""}.
     * @param parameters Its parameters.
     */
    public record ErrorType(String name, String description, VarlinkType.Struct parameters) {}
}
