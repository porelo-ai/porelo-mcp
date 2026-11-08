package org.fuin.porelo.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a Varlink interface description, as {@code org.varlink.service.GetInterfaceDescription}
 * returns it.
 *
 * <p>A comment directly before a declaration, a field or an enumeration value is its description; a
 * blank line ends a comment that belongs to nothing.
 */
public final class VarlinkIdl {

    /** The longest description read, so a daemon answering without end cannot fill the memory. */
    static final int MAX_LENGTH = 8 * 1024 * 1024;

    /** The deepest nesting of types read, so a nested description cannot overflow the stack. */
    static final int MAX_DEPTH = 64;

    private final String text;
    private int position;
    private int line = 1;
    private int depth;

    /** Each type name used, with the line it is first used on. */
    private final Map<String, Integer> used = new LinkedHashMap<>();

    private final StringBuilder comment = new StringBuilder();

    private VarlinkIdl(String text) {
        this.text = text;
    }

    /**
     * Parses a description.
     *
     * @param text The description.
     * @return The interface it describes.
     * @throws IllegalArgumentException If it is not a well-formed description, naming the line.
     */
    public static VarlinkInterface parse(String text) {
        if (text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "The interface description has "
                            + text.length()
                            + " characters, more than "
                            + MAX_LENGTH);
        }
        return new VarlinkIdl(text).interfaceDescription();
    }

    private VarlinkInterface interfaceDescription() {
        expectWord("interface");
        // A comment above 'interface' is the interface's, never its first declaration's.
        takeComment();
        final String name = name();
        final Map<String, VarlinkInterface.Declared> types = new LinkedHashMap<>();
        final List<VarlinkInterface.Method> methods = new ArrayList<>();
        final List<VarlinkInterface.ErrorType> errors = new ArrayList<>();
        // Each kind has its names to itself: the real contract has a type and a method both named
        // HandIn.
        final java.util.Set<String> typeNames = new java.util.HashSet<>();
        final java.util.Set<String> methodNames = new java.util.HashSet<>();
        final java.util.Set<String> errorNames = new java.util.HashSet<>();
        while (true) {
            skipSpace();
            if (position == text.length()) {
                break;
            }
            final String description = takeComment();
            final int at = line;
            final String keyword = name();
            switch (keyword) {
                case "type" -> {
                    final String typeName = unique(typeNames, name(), at);
                    skipSpace();
                    takeComment();
                    if (peek() != '(') {
                        throw error("A type is a struct or an enumeration in parentheses");
                    }
                    types.put(
                            typeName,
                            new VarlinkInterface.Declared(typeName, description, parenthesized()));
                }
                case "method" -> {
                    final String methodName = unique(methodNames, name(), at);
                    final VarlinkType.Struct input = struct();
                    expectArrow();
                    final VarlinkType.Struct output = struct();
                    methods.add(
                            new VarlinkInterface.Method(methodName, description, input, output));
                }
                case "error" -> {
                    final String errorName = unique(errorNames, name(), at);
                    errors.add(new VarlinkInterface.ErrorType(errorName, description, struct()));
                }
                default ->
                        throw error(
                                "Expected 'type', 'method' or 'error', found '" + keyword + "'");
            }
        }
        for (final Map.Entry<String, Integer> named : used.entrySet()) {
            if (!types.containsKey(named.getKey())) {
                throw new IllegalArgumentException(
                        "Line "
                                + named.getValue()
                                + ": the type '"
                                + named.getKey()
                                + "' is used but never declared");
            }
        }
        return new VarlinkInterface(name, types, methods, errors);
    }

    private String unique(java.util.Set<String> names, String name, int at) {
        if (!names.add(name)) {
            throw new IllegalArgumentException("Line " + at + ": '" + name + "' is declared twice");
        }
        return name;
    }

    private VarlinkType.Struct struct() {
        skipSpace();
        takeComment();
        if (peek() != '(') {
            throw error("Expected '('");
        }
        if (parenthesized() instanceof VarlinkType.Struct struct) {
            return struct;
        }
        throw error("Expected a struct, found an enumeration");
    }

    /** Reads {@code ( ... )}: a struct when its first entry has a type, else an enumeration. */
    private VarlinkType parenthesized() {
        if (++depth > MAX_DEPTH) {
            throw error("Types are nested deeper than " + MAX_DEPTH);
        }
        expect('(');
        final List<VarlinkType.Field> fields = new ArrayList<>();
        final List<VarlinkType.Member> values = new ArrayList<>();
        final java.util.Set<String> seen = new java.util.HashSet<>();
        // 0 until the first entry says which it is, then 1 for a struct, 2 for an enumeration.
        int kind = 0;
        skipSpace();
        String description = takeComment();
        if (peek() == ')') {
            position++;
            depth--;
            return new VarlinkType.Struct(List.of());
        }
        while (true) {
            final String entry = name();
            if (!seen.add(entry)) {
                throw error("'" + entry + "' appears twice");
            }
            skipSpace();
            final boolean typed = peek() == ':';
            if (kind == 0) {
                kind = typed ? 1 : 2;
            } else if (kind == 1 != typed) {
                throw error("A struct's fields and an enumeration's values are mixed");
            }
            if (typed) {
                position++;
                fields.add(new VarlinkType.Field(entry, description, type()));
            } else {
                values.add(new VarlinkType.Member(entry, description));
            }
            skipSpace();
            takeComment();
            final char next = peek();
            position++;
            if (next == ')') {
                break;
            }
            if (next != ',') {
                throw error("Expected ',' or ')'");
            }
            skipSpace();
            description = takeComment();
        }
        depth--;
        return kind == 1 ? new VarlinkType.Struct(fields) : new VarlinkType.Enum(values);
    }

    private VarlinkType type() {
        skipSpace();
        takeComment();
        final char first = peek();
        if (first == '?') {
            position++;
            skipSpace();
            // Refused before recursing, so a run of them cannot exhaust the stack.
            if (peek() == '?') {
                throw error("'??' is not a type");
            }
            return new VarlinkType.Maybe(type());
        }
        if (first == '[') {
            position++;
            skipSpace();
            final boolean dict = peek() != ']';
            if (dict) {
                expectWord("string");
            }
            expect(']');
            if (++depth > MAX_DEPTH) {
                throw error("Types are nested deeper than " + MAX_DEPTH);
            }
            final VarlinkType element = type();
            depth--;
            return dict ? new VarlinkType.Dict(element) : new VarlinkType.Array(element);
        }
        if (first == '(') {
            return parenthesized();
        }
        final int at = line;
        final String word = name();
        return switch (word) {
            case "bool" -> VarlinkType.Builtin.BOOL;
            case "int" -> VarlinkType.Builtin.INT;
            case "float" -> VarlinkType.Builtin.FLOAT;
            case "string" -> VarlinkType.Builtin.STRING;
            case "object" -> VarlinkType.Builtin.OBJECT;
            default -> {
                used.putIfAbsent(word, at);
                yield new VarlinkType.Named(word);
            }
        };
    }

    private void expectArrow() {
        skipSpace();
        takeComment();
        if (!text.startsWith("->", position)) {
            throw error("Expected '->'");
        }
        position += 2;
    }

    private void expect(char wanted) {
        skipSpace();
        takeComment();
        if (peek() != wanted) {
            throw error("Expected '" + wanted + "'");
        }
        position++;
    }

    private void expectWord(String wanted) {
        skipSpace();
        final String found = name();
        if (!found.equals(wanted)) {
            throw error("Expected '" + wanted + "', found '" + found + "'");
        }
    }

    /** Reads a name: letters, digits, '_' and, in an interface's name, '.' and '-'. */
    private String name() {
        skipSpace();
        final int start = position;
        while (position < text.length()) {
            final char c = text.charAt(position);
            if (Character.isLetterOrDigit(c) && c < 128 || c == '_' || c == '.' || c == '-') {
                position++;
            } else {
                break;
            }
        }
        if (start == position) {
            throw error(position == text.length() ? "Unexpected end" : "Expected a name");
        }
        return text.substring(start, position);
    }

    private char peek() {
        if (position == text.length()) {
            throw error("Unexpected end");
        }
        return text.charAt(position);
    }

    /**
     * Skips white space and gathers comments into {@link #comment}; a blank line drops what was
     * gathered, since it belongs to nothing.
     */
    private void skipSpace() {
        int newlines = 0;
        while (position < text.length()) {
            final char c = text.charAt(position);
            if (c == '\n') {
                line++;
                newlines++;
                if (newlines > 1) {
                    comment.setLength(0);
                }
                position++;
            } else if (c == ' ' || c == '\t' || c == '\r') {
                position++;
            } else if (c == '#') {
                final int end = endOfLine();
                final String content = text.substring(position + 1, end).strip();
                if (!comment.isEmpty()) {
                    comment.append('\n');
                }
                comment.append(content);
                position = end;
                newlines = 0;
            } else {
                return;
            }
        }
    }

    private int endOfLine() {
        final int end = text.indexOf('\n', position);
        return end < 0 ? text.length() : end;
    }

    private String takeComment() {
        final String taken = comment.toString().strip();
        comment.setLength(0);
        return taken;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("Line " + line + ": " + message);
    }
}
