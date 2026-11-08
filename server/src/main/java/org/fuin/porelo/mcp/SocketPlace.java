package org.fuin.porelo.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;

/**
 * Where the MCP socket may be bound: a path that does not exist yet, in a directory of this
 * process's own uid with mode {@code 0700}, which is no symbolic link.
 */
final class SocketPlace {

    private SocketPlace() {}

    /**
     * Checks the place and names the directory's owner, against whom a peer is compared.
     *
     * @param socket The socket's path.
     * @return The directory's owner, which is this process's own user.
     * @throws StartRefused When the place is refused, saying why.
     */
    static UserPrincipal check(Path socket) throws StartRefused {
        final Path directory = socket.toAbsolutePath().getParent();
        if (directory == null) {
            throw new StartRefused("The socket " + socket + " has no directory");
        }
        try {
            final PosixFileAttributes attributes =
                    Files.readAttributes(
                            directory, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                throw new StartRefused(
                        "The socket's directory " + directory + " is no directory of its own");
            }
            final int uid =
                    ((Number) Files.getAttribute(directory, "unix:uid", LinkOption.NOFOLLOW_LINKS))
                            .intValue();
            if (uid != ownUid()) {
                throw new StartRefused(
                        "The socket's directory " + directory + " belongs to uid " + uid);
            }
            final String mode = PosixFilePermissions.toString(attributes.permissions());
            if (!mode.equals("rwx------")) {
                throw new StartRefused(
                        "The socket's directory "
                                + directory
                                + " has mode "
                                + mode
                                + ", not rwx------");
            }
            if (Files.exists(socket, LinkOption.NOFOLLOW_LINKS)) {
                throw new StartRefused("The socket " + socket + " exists already");
            }
            return attributes.owner();
        } catch (IOException ex) {
            throw new StartRefused(
                    "The socket's directory " + directory + " cannot be read: " + ex.getMessage());
        }
    }

    /**
     * This process's real uid, from the kernel: its /proc entries may be root's when it is not
     * dumpable.
     */
    static long ownUid() throws IOException {
        for (final String line : Files.readAllLines(Path.of("/proc/self/status"))) {
            if (line.startsWith("Uid:")) {
                return Long.parseLong(line.trim().split("\\s+")[1]);
            }
        }
        throw new IOException("/proc/self/status names no uid");
    }
}
