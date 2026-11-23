package org.fuin.porelo.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code porelo-mcp --check} says "reached" only when it reached the daemon and read its methods.
 */
class CheckCommandTest {

    @TempDir Path directory;

    // Harm: a person told the tools work when the daemon gives others, or none.
    @Test
    void againstTheDaemonItPrintsItsVersionAndExactlyTheMethodsItDescribes() throws Exception {
        String description = StandInDaemon.tasks1();
        try (var daemon = new StandInDaemon(directory, description, Map.of())) {
            Ran ran = run(Map.of(), "--check", "--daemon-socket", daemon.socket().toString());

            assertThat(ran.code).as("reached").isEqualTo(0);
            List<String> lines = new ArrayList<>(ran.out.lines().toList());
            assertThat(lines.remove(0))
                    .as("the first line names the version and the socket")
                    .contains("9.9.9-standin")
                    .contains(daemon.socket().toString());
            assertThat(lines)
                    .as("one line per method, as described")
                    .containsExactlyElementsOf(ContractToolsTest.methodsDeclaredIn(description));
        }
    }

    // Harm: a health check that says "reached" when it was not.
    @Test
    void againstNoSocketItExitsThreeAndNamesThePathItTried() throws Exception {
        Path missing = directory.resolve("no-daemon.sock");

        Ran ran = run(Map.of(), "--check", "--daemon-socket", missing.toString());

        assertThat(ran.code).as("not reached").isEqualTo(3);
        assertThat(ran.err)
                .as("names what it asked and where")
                .contains("GetInfo")
                .contains(missing.toString());
        assertThat(ran.out).as("nothing that reads as reached").isEmpty();
    }

    // Harm: a daemon whose interface cannot be read reported as reached, its tools then missing.
    @Test
    void aDescriptionThatDoesNotParseExitsThreeAndSaysSo() throws Exception {
        try (var daemon =
                new StandInDaemon(
                        directory, "interface org.fuin.porelo.Tasks1\nmethod Broken(", Map.of())) {
            Ran ran = run(Map.of(), "--check", "--daemon-socket", daemon.socket().toString());

            assertThat(ran.code).as("not reached").isEqualTo(3);
            assertThat(ran.err).as("says the description is broken").contains("does not parse");
            assertThat(ran.out).as("nothing that reads as reached").isEmpty();
        }
    }

    // Harm: a check that silently asks a socket nobody named when the environment is empty.
    @Test
    void withoutARuntimeDirectoryAndNoSocketGivenItExitsThreeAndSaysSo() throws Exception {
        Ran unset = run(Map.of(), "--check");
        Ran empty = run(Map.of("XDG_RUNTIME_DIR", ""), "--check");

        assertThat(unset.code).as("unset").isEqualTo(3);
        assertThat(empty.code).as("empty").isEqualTo(3);
        assertThat(unset.err).as("names the variable when unset").contains("XDG_RUNTIME_DIR");
        assertThat(empty.err).as("names the variable when empty").contains("XDG_RUNTIME_DIR");
    }

    // Harm: the check asking another socket than the daemon's own default.
    @Test
    void withoutASocketGivenItAsksTheDaemonsDefaultSocket() throws Exception {
        Path runtime = Files.createDirectory(directory.resolve("runtime"));
        Path porelo = Files.createDirectory(runtime.resolve("porelo"));
        try (var daemon = new StandInDaemon(porelo, StandInDaemon.tasks1(), Map.of())) {
            Files.move(daemon.socket(), porelo.resolve("porelod.sock"));

            Ran ran = run(Map.of("XDG_RUNTIME_DIR", runtime.toString()), "--check");

            assertThat(ran.code)
                    .as("reached at the default socket; errors: " + ran.err)
                    .isEqualTo(0);
            assertThat(ran.out)
                    .as("names the default socket")
                    .contains(porelo.resolve("porelod.sock").toString());
        }
    }

    // Harm: "reached" said of a socket that answers, but is no Porelo daemon or names no version.
    @Test
    void anotherInterfaceOrNoVersionIsNotReached() throws Exception {
        Path one = Files.createDirectory(directory.resolve("other-interface"));
        Path two = Files.createDirectory(directory.resolve("no-version"));
        Path three = Files.createDirectory(directory.resolve("no-tasks1"));
        try (var other =
                        new StandInDaemon(
                                one, "interface org.example.Other1\nmethod X() -> ()\n", Map.of());
                var blank =
                        new StandInDaemon(
                                two,
                                "org.fuin.porelo.Tasks1",
                                StandInDaemon.tasks1(),
                                "",
                                Map.of());
                var elsewhere =
                        new StandInDaemon(
                                three,
                                "org.example.Other1",
                                "interface org.example.Other1\nmethod X() -> ()\n",
                                "1",
                                Map.of())) {
            Ran otherRan = run(Map.of(), "--check", "--daemon-socket", other.socket().toString());
            Ran blankRan = run(Map.of(), "--check", "--daemon-socket", blank.socket().toString());
            Ran elsewhereRan =
                    run(Map.of(), "--check", "--daemon-socket", elsewhere.socket().toString());

            assertThat(otherRan.code).as("a description of another interface").isEqualTo(3);
            assertThat(otherRan.err)
                    .as("names the interface it got")
                    .contains("org.example.Other1");
            assertThat(blankRan.code).as("a blank version").isEqualTo(3);
            assertThat(blankRan.err).as("says the version is missing").contains("version");
            assertThat(elsewhereRan.code).as("a daemon without Tasks1").isEqualTo(3);
            assertThat(elsewhereRan.err)
                    .as("names what it asked")
                    .contains("GetInterfaceDescription")
                    .contains("org.fuin.porelo.Tasks1");
            for (Ran each : List.of(otherRan, blankRan, elsewhereRan)) {
                assertThat(each.out).as("nothing that reads as reached").isEmpty();
            }
        }
    }

    // Harm: a wrong argument read as a broken daemon, or crashing with a stack trace.
    @Test
    void aWrongArgumentOrAnUnusablePathExitsTwoAndSaysWhich() {
        Ran unknown = run(Map.of(), "--check", "--socket", "x");
        Ran nul = run(Map.of(), "--check", "--daemon-socket", "a\0b");

        assertThat(unknown.code).as("an unknown argument").isEqualTo(2);
        assertThat(unknown.err).as("names it").contains("--socket");
        assertThat(nul.code).as("a path holding a NUL byte").isEqualTo(2);
        assertThat(nul.err).as("says the path is unusable").contains("--daemon-socket");
    }

    // Harm: a serve started with wrong arguments, read by the daemon as a broken daemon or a crash.
    @Test
    void serveWithAWrongArgumentOrAnUnusablePathExitsTwoBeforeReadingTheToken() {
        Ran unknown =
                run(
                        Map.of(),
                        "serve",
                        "--task",
                        "t",
                        "--socket",
                        "s",
                        "--daemon-socket",
                        "d",
                        "--more",
                        "x");
        Ran missing = run(Map.of(), "serve", "--task", "t", "--socket", "s");
        Ran nul = run(Map.of(), "serve", "--task", "t", "--socket", "a\0b", "--daemon-socket", "d");

        assertThat(unknown.code).as("an unknown argument").isEqualTo(2);
        assertThat(missing.code).as("a missing argument").isEqualTo(2);
        assertThat(missing.err).as("names it").contains("--daemon-socket");
        assertThat(nul.code).as("a path holding a NUL byte").isEqualTo(2);
        assertThat(nul.err)
                .as("says the path is unusable, not the token")
                .contains("--socket")
                .doesNotContain("token");
    }

    // Harm: a broken limits line from porelod taken as all factors at 1.0, or read as some other
    // fault, so the daemon cannot say what is wrong.
    @Test
    void aRefusedLimitsLineExitsSixtyFourInEveryModeBeforeAnythingIsDone() {
        Path socket = directory.resolve("s");
        List<Ran> refused =
                List.of(
                        run(
                                Map.of(),
                                "serve",
                                "--task",
                                "t",
                                "--socket",
                                socket.toString(),
                                "--daemon-socket",
                                "d",
                                "--limits",
                                "services=0"),
                        run(Map.of(), "stdio", "--limits", "speed=2.0"),
                        run(Map.of(), "--check", "--limits", "services=1.0,services=2.0"));

        assertThat(refused)
                .as("each refused with 64, naming --limits, before the token or the daemon")
                .allSatisfy(
                        ran -> {
                            assertThat(ran.code).as("exit code: %s", ran.err).isEqualTo(64);
                            assertThat(ran.err).as("names the option").contains("--limits: ");
                            assertThat(ran.out).as("no ready, no answer").isEmpty();
                        });
        assertThat(Files.exists(socket)).as("no socket bound").isFalse();
    }

    // Harm: a program refusing the limits line every program porelod starts is given, or the
    // window and IntelliJ pass on.
    @Test
    void aGoodLimitsLineIsTakenByStdioAndCheck() {
        Path none = Path.of("/nonexistent/porelod.sock");
        Ran check =
                run(
                        Map.of(),
                        "--check",
                        "--limits",
                        "services=2.0",
                        "--daemon-socket",
                        none.toString());
        Ran stdio = run(Map.of(), "stdio", "--daemon-socket", none.toString(), "--limits", "");

        assertThat(check.code)
                .as("taken; the daemon is what is missing: %s", check.err)
                .isEqualTo(3);
        assertThat(stdio.code).as("an empty line is all four at 1.0: %s", stdio.err).isEqualTo(3);
    }

    // Harm: an argument read as the limits line, or the line read as another option's value, so a
    // task starts with factors porelod never gave.
    @Test
    void theLimitsLineIsTakenOnlyAsAnOptionOfItsOwnWhereverItStands() throws Exception {
        Main.Arguments first =
                Main.Arguments.of(List.of("--limits", "services=2.0", "--task", "t"));
        Main.Arguments last = Main.Arguments.of(List.of("--task", "t", "--limits", "services=2.0"));
        Main.Arguments asValue =
                Main.Arguments.of(List.of("--task", "--limits", "services=2.0", "x"));
        Main.Arguments empty = Main.Arguments.of(List.of("--limits", ""));

        assertThat(List.of(first, last))
                .as("taken first or last, the rest kept in order")
                .allSatisfy(
                        each -> {
                            assertThat(each.limits().services(java.time.Duration.ofSeconds(30)))
                                    .as("services 2.0")
                                    .isEqualTo(java.time.Duration.ofSeconds(60));
                            assertThat(each.rest()).as("the rest").containsExactly("--task", "t");
                        });
        assertThat(asValue.limits())
                .as("--limits as another option's value is no line")
                .isEqualTo(org.fuin.porelo.wire.LimitFactors.DEFAULT);
        assertThat(asValue.rest())
                .as("left for the options check to refuse")
                .containsExactly("--task", "--limits", "services=2.0", "x");
        assertThat(empty.limits())
                .as("an empty line is all four at 1.0")
                .isEqualTo(org.fuin.porelo.wire.LimitFactors.DEFAULT);
        assertThat(run(Map.of(), "--check", "--limits").code)
                .as("no value: a wrong argument")
                .isEqualTo(2);
        assertThat(run(Map.of(), "--check", "--limits", "", "--limits", "services=2.0").code)
                .as("given twice: a wrong argument")
                .isEqualTo(2);
    }

    record Ran(int code, String out, String err) {}

    static Ran run(Map<String, String> environment, String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code =
                Main.run(
                        args,
                        new ByteArrayInputStream(new byte[0]),
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8),
                        environment);
        return new Ran(
                code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
}
