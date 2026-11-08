package org.fuin.porelo.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The daemon's description is read as it is written, or refused with the line that is wrong. */
class VarlinkIdlTest {

    // Harm: a parameter the agent is told is optional when the daemon needs it, or of another type.
    @Test
    void readsEveryKindOfTypeWithItsOptionalityAndItsComments() {
        VarlinkInterface parsed =
                VarlinkIdl.parse(
                        """
                        # The header belongs to nothing.

                        interface org.example.Test1

                        # A colour.
                        type Colour (
                          # The red one.
                          RED,
                          GREEN
                        )

                        # Paints.
                        method Paint(
                          # What to paint.
                          what: string,
                          colour: ?Colour,
                          layers: []int,
                          labels: [string]string,
                          extra: object,
                          ratio: ?float,
                          kind: (fine, coarse)
                        ) -> (done: bool)
                        error Refused (reason: string)
                        """);

        assertThat(parsed.name()).as("the interface's name").isEqualTo("org.example.Test1");
        VarlinkInterface.Method paint = parsed.methods().getFirst();
        assertThat(paint.description()).as("the method's comment").isEqualTo("Paints.");
        assertThat(paint.input().fields())
                .as("each field with its own type and comment")
                .containsExactly(
                        new VarlinkType.Field("what", "What to paint.", VarlinkType.Builtin.STRING),
                        new VarlinkType.Field(
                                "colour",
                                "",
                                new VarlinkType.Maybe(new VarlinkType.Named("Colour"))),
                        new VarlinkType.Field(
                                "layers", "", new VarlinkType.Array(VarlinkType.Builtin.INT)),
                        new VarlinkType.Field(
                                "labels", "", new VarlinkType.Dict(VarlinkType.Builtin.STRING)),
                        new VarlinkType.Field("extra", "", VarlinkType.Builtin.OBJECT),
                        new VarlinkType.Field(
                                "ratio", "", new VarlinkType.Maybe(VarlinkType.Builtin.FLOAT)),
                        new VarlinkType.Field(
                                "kind",
                                "",
                                new VarlinkType.Enum(
                                        List.of(
                                                new VarlinkType.Member("fine", ""),
                                                new VarlinkType.Member("coarse", "")))));
        assertThat(parsed.types().get("Colour"))
                .as("a declared enumeration with its comments")
                .isEqualTo(
                        new VarlinkInterface.Declared(
                                "Colour",
                                "A colour.",
                                new VarlinkType.Enum(
                                        List.of(
                                                new VarlinkType.Member("RED", "The red one."),
                                                new VarlinkType.Member("GREEN", "")))));
        assertThat(parsed.errors())
                .extracting(VarlinkInterface.ErrorType::name)
                .as("the declared error")
                .containsExactly("Refused");

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> input = Tool.of(parsed).getFirst().inputSchema();
        assertThat((List<Object>) input.get("required"))
                .as("only what has no ? is required")
                .containsExactly("what", "layers", "labels", "extra", "kind");
    }

    // Harm: tools built from a misread description, offered as if they were the contract.
    @Test
    void aWrongDescriptionIsRefusedNamingItsLine() {
        assertThatThrownBy(
                        () ->
                                VarlinkIdl.parse(
                                        "interface a.B1\n\nmethod X(a: int) -> (b: Missing)\n"))
                .as("a type used but never declared, at the line it is used")
                .hasMessageStartingWith("Line 3:")
                .hasMessageContaining("Missing");
        assertThatThrownBy(() -> VarlinkIdl.parse("interface a.B1\nmethod X(a: int) (b: int)\n"))
                .as("a missing arrow")
                .hasMessageStartingWith("Line 2:");
        assertThatThrownBy(
                        () ->
                                VarlinkIdl.parse(
                                        "interface a.B1\nmethod X() -> ()\nmethod X() -> ()\n"))
                .as("a method declared twice")
                .hasMessageContaining("declared twice");
        assertThatThrownBy(() -> VarlinkIdl.parse("interface a.B1\ntype T (a: int, B)\n"))
                .as("fields and values mixed")
                .hasMessageContaining("mixed");
    }

    // Harm: a description that crashes or exhausts the process instead of being refused.
    @Test
    void aDescriptionNestedTooDeepOrTooLongIsRefusedNotACrash() {
        String deep =
                "interface a.B1\nmethod X(a: "
                        + "[]".repeat(VarlinkIdl.MAX_DEPTH + 1)
                        + "int) -> ()\n";
        assertThatThrownBy(() -> VarlinkIdl.parse(deep))
                .as("arrays nested too deep")
                .hasMessageContaining("nested deeper");

        String nested =
                "interface a.B1\nmethod X(a: "
                        + "(b: ".repeat(VarlinkIdl.MAX_DEPTH + 1)
                        + "int"
                        + ")".repeat(VarlinkIdl.MAX_DEPTH + 1)
                        + ") -> ()\n";
        assertThatThrownBy(() -> VarlinkIdl.parse(nested))
                .as("structs nested too deep")
                .hasMessageContaining("nested deeper");

        String maybes = "interface a.B1\nmethod X(a: " + "?".repeat(20_000) + "int) -> ()\n";
        assertThatThrownBy(() -> VarlinkIdl.parse(maybes))
                .as("a run of ? is refused, not a stack overflow")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'??'");

        String longOne = "interface a.B1\n#" + "x".repeat(VarlinkIdl.MAX_LENGTH);
        assertThatThrownBy(() -> VarlinkIdl.parse(longOne))
                .as("a description too long")
                .hasMessageContaining("more than");
    }

    // Harm: an agent reading the interface's own comment as the words of its first method.
    @Test
    void aCommentBeforeTheInterfaceBelongsToNoDeclaration() {
        VarlinkInterface parsed =
                VarlinkIdl.parse("# The interface.\ninterface a.B1\nmethod X() -> ()\n");

        assertThat(parsed.methods().getFirst().description())
                .as("the first method has no comment of its own")
                .isEmpty();
    }

    // Harm: an agent losing what each value of a declared enumeration means.
    @Test
    void aDeclaredEnumerationKeepsItsOwnAndItsValuesWords() {
        VarlinkInterface parsed =
                VarlinkIdl.parse(
                        """
                        interface a.B1

                        # A colour.
                        type Colour (
                          # The red one.
                          RED,
                          GREEN
                        )

                        method X(c: Colour) -> ()
                        """);

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> defs =
                (java.util.Map<String, Object>)
                        Tool.of(parsed).getFirst().inputSchema().get("$defs");

        assertThat(((java.util.Map<?, ?>) defs.get("Colour")).get("description"))
                .as("the type's comment, then each value's")
                .isEqualTo("A colour.\nRED: The red one.");
    }
}
