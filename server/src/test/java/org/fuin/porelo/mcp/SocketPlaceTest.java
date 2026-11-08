package org.fuin.porelo.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The MCP socket is bound only in a directory of its own user's, mode 0700, reached without a
 * symbolic link.
 */
class SocketPlaceTest {

    @TempDir Path directory;

    // Harm: a socket bound through a link into a directory someone else chose.
    @Test
    void aDirectoryThatIsASymbolicLinkIsRefusedWrittenEitherWay() throws Exception {
        Path real = Files.createDirectory(directory.resolve("real"));
        Files.setPosixFilePermissions(real, PosixFilePermissions.fromString("rwx------"));
        Path link = Files.createSymbolicLink(directory.resolve("link"), real);

        assertThat(SocketPlace.check(real.resolve("t.sock")))
                .as("the real directory is taken")
                .isNotNull();
        assertThatThrownBy(() -> SocketPlace.check(link.resolve("t.sock")))
                .as("a link as the directory")
                .isInstanceOf(StartRefused.class);
        assertThatThrownBy(() -> SocketPlace.check(Path.of(link + "/./t.sock")))
                .as("a link hidden behind a dot")
                .isInstanceOf(StartRefused.class);
        assertThatThrownBy(() -> SocketPlace.check(Path.of(real + "/../real/t.sock")))
                .as("a path with a dot-dot segment")
                .isInstanceOf(StartRefused.class);
    }
}
