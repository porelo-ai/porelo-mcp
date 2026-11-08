package org.fuin.porelo.mcp;

import java.util.List;

/** A type in a Varlink interface description. */
public sealed interface VarlinkType {

    /** One of Varlink's built-in types. */
    enum Builtin implements VarlinkType {
        /** {@code bool}. */
        BOOL,
        /** {@code int}. */
        INT,
        /** {@code float}. */
        FLOAT,
        /** {@code string}. */
        STRING,
        /** {@code object}: any JSON object. */
        OBJECT
    }

    /**
     * A type named in the interface, resolved by its name.
     *
     * @param name The type's name.
     */
    record Named(String name) implements VarlinkType {}

    /**
     * {@code ?T}: a value that may be absent.
     *
     * @param inner The type when present.
     */
    record Maybe(VarlinkType inner) implements VarlinkType {}

    /**
     * {@code []T}.
     *
     * @param element The type of each element.
     */
    record Array(VarlinkType element) implements VarlinkType {}

    /**
     * {@code [string]T}: a map from string keys.
     *
     * @param value The type of each value.
     */
    record Dict(VarlinkType value) implements VarlinkType {}

    /**
     * {@code (a: T, b: U)}.
     *
     * @param fields The fields, in their order.
     */
    record Struct(List<Field> fields) implements VarlinkType {

        /** Copies the list, so the struct cannot change. */
        public Struct {
            fields = List.copyOf(fields);
        }
    }

    /**
     * {@code (A, B, C)}.
     *
     * @param values The values, in their order.
     */
    record Enum(List<Member> values) implements VarlinkType {

        /** Copies the list, so the enumeration cannot change. */
        public Enum {
            values = List.copyOf(values);
        }
    }

    /**
     * A field of a struct.
     *
     * @param name Its name.
     * @param description The comment written before it, or {@code ""}.
     * @param type Its type.
     */
    record Field(String name, String description, VarlinkType type) {}

    /**
     * A value of an enumeration.
     *
     * @param name Its name.
     * @param description The comment written before it, or {@code ""}.
     */
    record Member(String name, String description) {}
}
