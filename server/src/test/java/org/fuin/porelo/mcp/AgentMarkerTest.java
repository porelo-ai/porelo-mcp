package org.fuin.porelo.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A method of the daemon's contract becomes a tool only when the last line of its comment marks it
 * for agents, in the one form porelo writes; every other method is left out, and one whose marker
 * is missing or malformed is named.
 */
class AgentMarkerTest {

    private static final String INTERFACE =
            """
            interface org.fuin.porelo.Tasks1

            # Lists the tasks.
            # x-mcp: true
            method Listed() -> ()

            # Stops every task.
            # x-mcp: false it stops every task at once
            method Refused() -> ()

            # Has no marker.
            method Unmarked() -> ()

            # x-mcp: false
            method NoReason() -> ()

            # x-mcp:true
            method Malformed() -> ()

            # x-mcp: true
            # Says more after its marker.
            method NotLast() -> ()

            # x-mcp: false a reason
            # x-mcp: true
            method Twice() -> ()

            # x-mcp: false %s
            method LongReason() -> ()
            """
                    .formatted("r".repeat(AgentMarker.MOST_REASON + 1));

    // Harm: a contract this program is built against whose markers it reads otherwise than porelo
    // wrote them, so a method meant for agents goes missing or one kept from them is offered.
    @Test
    void everyMethodOfThePinnedContractCarriesAMarkerThisProgramReads() throws Exception {
        final String contract = StandInDaemon.tasks1();
        final ByteArrayOutputStream said = new ByteArrayOutputStream();

        final List<Tool> tools =
                Main.toolsOf(contract, new PrintStream(said, true, StandardCharsets.UTF_8));

        assertThat(said.toString(StandardCharsets.UTF_8))
                .as("no method without a valid marker")
                .isEmpty();
        assertThat(tools)
                .as("the tools are the methods the pin marks true, and some are kept from agents")
                .extracting(Tool::name)
                .containsExactlyInAnyOrderElementsOf(StandInDaemon.meantIn(contract))
                .hasSizeLessThan(ContractToolsTest.methodsDeclaredIn(contract).size())
                .contains("McpMethods")
                .doesNotContain("Panic", "Approve");
    }

    // Harm: a method porelo never judged fit for agents, or judged unfit, offered to one.
    @Test
    void onlyAMethodMarkedTrueIsAToolAndEveryBrokenMarkerIsNamed() {
        final ByteArrayOutputStream said = new ByteArrayOutputStream();

        final List<Tool> tools =
                Main.toolsOf(INTERFACE, new PrintStream(said, true, StandardCharsets.UTF_8));

        assertThat(tools)
                .as("the one method marked true")
                .extracting(Tool::name)
                .containsExactly("Listed");
        assertThat(tools.getFirst().description())
                .as("its description without the marker")
                .isEqualTo("Lists the tasks.");
        final String err = said.toString(StandardCharsets.UTF_8);
        assertThat(err.lines())
                .as("each method without a valid marker named, one line each")
                .hasSize(6)
                .anySatisfy(
                        line -> assertThat(line).as("unmarked").startsWith("porelo-mcp: Unmarked "))
                .anySatisfy(
                        line ->
                                assertThat(line)
                                        .as("no reason")
                                        .startsWith("porelo-mcp: NoReason "))
                .anySatisfy(
                        line ->
                                assertThat(line)
                                        .as("malformed")
                                        .startsWith("porelo-mcp: Malformed "))
                .anySatisfy(
                        line -> assertThat(line).as("not last").startsWith("porelo-mcp: NotLast "))
                .anySatisfy(line -> assertThat(line).as("twice").startsWith("porelo-mcp: Twice "))
                .anySatisfy(
                        line ->
                                assertThat(line)
                                        .as("too long")
                                        .startsWith("porelo-mcp: LongReason "));
        assertThat(err)
                .as("a method marked false with its reason is left out without a word")
                .doesNotContain("Refused");
    }
}
