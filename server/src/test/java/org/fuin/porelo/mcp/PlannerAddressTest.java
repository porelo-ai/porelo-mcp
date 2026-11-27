package org.fuin.porelo.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The planner's address file is believed only where no other account could have placed or read it,
 * as the window believes it, and its token never shows.
 */
class PlannerAddressTest {

    private static final String TOKEN = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";

    @TempDir Path parent;

    private Path runtime;

    private Path directory;

    private long own;

    @BeforeEach
    void runtimeDirectoryAsTheSystemMakesIt() throws Exception {
        runtime = owned(Files.createDirectory(parent.resolve("run")));
        directory = owned(Files.createDirectory(runtime.resolve(PlannerAddress.DIRECTORY)));
        own = SocketPlace.ownUid();
    }

    // Harm: calls signed for a planner that is not there, or a token shown in a log.
    @Test
    void aFileAsThePlannerWritesItGivesItsPortAndHidesItsToken() throws Exception {
        write("{\"port\":41234,\"token\":\"" + TOKEN + "\",\"pid\":7,\"log\":\"/x\"}");

        final PlannerAddress address = PlannerAddress.read(runtime.toString(), own);

        assertThat(address.port()).as("the port written").isEqualTo(41234);
        assertThat(address.token()).as("the token written").isEqualTo(TOKEN);
        assertThat(address.toString()).as("what a log line shows").doesNotContain(TOKEN);
    }

    // Harm: a dead planner taken for a refusal, or a refusal hidden as a missing planner.
    @Test
    void noFileMeansNoPlannerRuns() {
        assertThat(reason()).as("no planner.json").isEqualTo(Planner.Reason.NOT_RUNNING);
    }

    // Harm: a token another account could read, or a file another account placed.
    @Test
    void aFileOthersCouldReadOrPlaceIsRefused() throws Exception {
        final Path file = write("{\"port\":41234,\"token\":\"" + TOKEN + "\"}");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
        assertThat(reason()).as("a file the group may read").isEqualTo(refused());

        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-x---"));
        assertThat(reason()).as("a directory the group may open").isEqualTo(refused());

        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        assertThat(
                        catchThrowableOfType(
                                Planner.Unavailable.class,
                                () -> PlannerAddress.read(runtime.toString(), own + 1)))
                .as("a file of another account")
                .extracting(Planner.Unavailable::reason)
                .isEqualTo(refused());
    }

    // Harm: a file read where another account may change what lies there.
    @Test
    void aRuntimeDirectoryOthersCouldChangeIsRefused() throws Exception {
        write("{\"port\":41234,\"token\":\"" + TOKEN + "\"}");
        Files.setPosixFilePermissions(runtime, PosixFilePermissions.fromString("rwxr-x---"));
        assertThat(reason()).as("a runtime directory the group may open").isEqualTo(refused());
        Files.setPosixFilePermissions(runtime, PosixFilePermissions.fromString("rwx------"));

        final Path open = Files.createDirectory(parent.resolve("open"));
        Files.setPosixFilePermissions(open, PosixFilePermissions.fromString("rwxrwxrwx"));
        final Path below = owned(Files.createDirectory(open.resolve("run")));
        owned(Files.createDirectory(below.resolve(PlannerAddress.DIRECTORY)));
        assertThat(
                        catchThrowableOfType(
                                Planner.Unavailable.class,
                                () -> PlannerAddress.read(below.toString(), own)))
                .as("a directory above it that others may write, not sticky")
                .extracting(Planner.Unavailable::reason)
                .isEqualTo(refused());

        final Path link = Files.createSymbolicLink(parent.resolve("link"), open);
        assertThat(
                        catchThrowableOfType(
                                Planner.Unavailable.class,
                                () -> PlannerAddress.read(link + "/run", own)))
                .as("the same directory reached through a link of this account")
                .extracting(Planner.Unavailable::reason)
                .isEqualTo(refused());
    }

    // Harm: a link that sends the token's read to a file someone else chose.
    @Test
    void aFileReachedThroughALinkIsRefused() throws Exception {
        final Path real = owned(Files.createFile(parent.resolve("elsewhere.json")));
        Files.writeString(real, "{\"port\":41234,\"token\":\"" + TOKEN + "\"}");
        Files.createSymbolicLink(directory.resolve(PlannerAddress.FILE), real);

        assertThat(reason()).as("planner.json as a link").isEqualTo(refused());
        assertThat(
                        catchThrowableOfType(
                                Planner.Unavailable.class,
                                () -> PlannerAddress.read(runtime + "/../run", own)))
                .as("a runtime directory named through ..")
                .extracting(Planner.Unavailable::reason)
                .isEqualTo(refused());
    }

    // Harm: a port or token the planner never wrote, used to sign and send the person's calls.
    @Test
    void aFileThePlannerNeverWritesIsRefused() throws Exception {
        for (final String text :
                new String[] {
                    "not json",
                    "{\"port\":0,\"token\":\"" + TOKEN + "\"}",
                    "{\"port\":41234.0,\"token\":\"" + TOKEN + "\"}",
                    "{\"port\":65536,\"token\":\"" + TOKEN + "\"}",
                    "{\"port\":41234,\"token\":\"\"}",
                    "{\"port\":41234,\"token\":\"a b\"}",
                    "{\"port\":41234}",
                    "{\"port\":41234,\"token\":\"" + "a".repeat(5000) + "\"}"
                }) {
            write(text);
            assertThat(reason())
                    .as(text.length() > 80 ? "a file too large" : text)
                    .isEqualTo(refused());
        }
    }

    private Planner.Reason reason() {
        return catchThrowableOfType(
                        Planner.Unavailable.class,
                        () -> PlannerAddress.read(runtime.toString(), own))
                .reason();
    }

    private static Planner.Reason refused() {
        return Planner.Reason.ADDRESS_REFUSED;
    }

    private Path write(String text) throws Exception {
        final Path file = directory.resolve(PlannerAddress.FILE);
        Files.deleteIfExists(file);
        Files.writeString(Files.createFile(file), text);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        return file;
    }

    private static Path owned(Path path) throws Exception {
        Files.setPosixFilePermissions(
                path,
                PosixFilePermissions.fromString(
                        Files.isDirectory(path) ? "rwx------" : "rw-------"));
        return path;
    }
}
