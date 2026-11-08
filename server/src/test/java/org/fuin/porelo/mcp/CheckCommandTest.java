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

        assertThat(ran.code).isEqualTo(3);
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

            assertThat(ran.code).isEqualTo(3);
            assertThat(ran.err).contains("does not parse");
            assertThat(ran.out).isEmpty();
        }
    }

    // Harm: a check that silently asks a socket nobody named when the environment is empty.
    @Test
    void withoutARuntimeDirectoryAndNoSocketGivenItExitsThreeAndSaysSo() throws Exception {
        Ran unset = run(Map.of(), "--check");
        Ran empty = run(Map.of("XDG_RUNTIME_DIR", ""), "--check");

        assertThat(unset.code).as("unset").isEqualTo(3);
        assertThat(empty.code).as("empty").isEqualTo(3);
        assertThat(unset.err).contains("XDG_RUNTIME_DIR");
        assertThat(empty.err).contains("XDG_RUNTIME_DIR");
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
            assertThat(ran.out).contains(porelo.resolve("porelod.sock").toString());
        }
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
