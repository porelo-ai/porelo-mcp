package org.fuin.porelo.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.JsonException;
import org.jspecify.annotations.Nullable;

/**
 * Where the person's planner listens and the token its calls are signed under, read from {@code
 * porelo-planner/planner.json} in the account's runtime directory under the checks the window holds
 * it to. The planner's lock beside it is never taken: a starting planner would race it, and a file
 * a dead planner left is found out by its proof.
 *
 * @param port The planner's port on the loopback address.
 * @param token The token every call is signed under; never sent, never shown.
 */
record PlannerAddress(int port, String token) {

    /** The directory under the runtime directory. */
    static final String DIRECTORY = "porelo-planner";

    /** The file the planner writes there. */
    static final String FILE = "planner.json";

    /** More than the planner ever writes. */
    private static final int MOST = 4096;

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._~+/=-]+");

    private static final int TYPE = 0xf000;
    private static final int DIRECTORY_TYPE = 0x4000;
    private static final int FILE_TYPE = 0x8000;
    private static final int LINK_TYPE = 0xa000;
    private static final int OTHERS_WRITE = 0x12;
    private static final int STICKY = 0x200;

    /** Never the token, so a log line or an error cannot show it. */
    @Override
    public String toString() {
        return "PlannerAddress[port=" + port + "]";
    }

    /**
     * Reads the address file of this account's planner.
     *
     * @param environment The process's environment, for {@code XDG_RUNTIME_DIR}.
     * @return The address.
     * @throws Planner.Unavailable {@code NOT_RUNNING} when there is no file, {@code
     *     ADDRESS_REFUSED} when it, or a directory above it, could have been placed by another
     *     account or is not as the planner writes it.
     */
    static PlannerAddress read(Map<String, String> environment) {
        final long own;
        try {
            own = SocketPlace.ownUid();
        } catch (IOException | RuntimeException ex) {
            throw refused("this account's uid is unknown: " + ex.getMessage(), ex);
        }
        final String given = environment.getOrDefault("XDG_RUNTIME_DIR", "");
        return read(given.isEmpty() ? "/run/user/" + own : given, own);
    }

    /**
     * Reads the address file under a runtime directory, checked against an account.
     *
     * @param runtime The runtime directory, an absolute path.
     * @param own The account's uid.
     * @return The address.
     * @throws Planner.Unavailable As {@link #read(Map)}.
     */
    static PlannerAddress read(String runtime, long own) {
        if (!runtime.startsWith("/")) {
            throw refused("XDG_RUNTIME_DIR is not an absolute path");
        }
        final List<String> steps = new ArrayList<>();
        for (final String step : runtime.split("/")) {
            if (step.equals(".") || step.equals("..")) {
                // A link could stand where the checks below see a directory.
                throw refused("XDG_RUNTIME_DIR names . or ..");
            }
            if (!step.isEmpty()) {
                steps.add(step);
            }
        }
        final Path base;
        try {
            base = Path.of("/", steps.toArray(String[]::new));
        } catch (InvalidPathException ex) {
            throw refused("XDG_RUNTIME_DIR is no usable path: " + ex.getReason(), ex);
        }
        Path above = Path.of("/");
        for (final String step : steps) {
            above(above, own);
            above = above.resolve(step);
        }
        final Path real = real(base, own);
        final Path directory = real.resolve(DIRECTORY);
        final Path file = directory.resolve(FILE);
        ownOnly(real, own, DIRECTORY_TYPE, file);
        ownOnly(directory, own, DIRECTORY_TYPE, file);
        ownOnly(file, own, FILE_TYPE, file);
        return parse(bounded(file), file);
    }

    /**
     * Reads the file's text: one JSON object with {@code port} and {@code token}.
     *
     * @param text The file's text.
     * @param file The file, for the words.
     * @return The address.
     */
    static PlannerAddress parse(String text, Path file) {
        final Object json;
        try {
            json = Json.parseNumberTypes(text);
        } catch (JsonException ex) {
            throw refused(file + " is not JSON");
        }
        if (!(json instanceof Map<?, ?> fields)
                || !(fields.get("port") instanceof BigInteger port)
                || !(fields.get("token") instanceof String token)) {
            throw refused(file + " lacks a port or a token");
        }
        if (port.signum() < 1 || port.compareTo(BigInteger.valueOf(65535)) > 0) {
            throw refused(file + " names a port out of range");
        }
        if (!TOKEN.matcher(token).matches()) {
            throw refused(file + " holds an empty or malformed token");
        }
        return new PlannerAddress(port.intValueExact(), token);
    }

    /**
     * The runtime directory reached without a link: every directory above it on its real path is
     * checked as well, so a link above it cannot lead the read where another account may change
     * what it finds.
     */
    private static Path real(Path base, long own) {
        final Path parent = base.getParent();
        final Path name = base.getFileName();
        if (parent == null || name == null) {
            throw refused("XDG_RUNTIME_DIR is the root directory", null);
        }
        final Path real;
        try {
            real = parent.toRealPath();
        } catch (NoSuchFileException ex) {
            throw notRunning(base.resolve(DIRECTORY).resolve(FILE), ex);
        } catch (IOException ex) {
            throw refused(parent + " cannot be resolved: " + ex.getMessage(), ex);
        }
        Path above = real.getRoot() == null ? Path.of("/") : real.getRoot();
        above(above, own);
        for (final Path step : real) {
            above = above.resolve(step);
            above(above, own);
        }
        return real.resolve(name);
    }

    private static String bounded(Path file) {
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            final byte[] bytes = in.readNBytes(MOST + 1);
            if (bytes.length > MOST) {
                throw refused(file + " is too large", null);
            }
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException ex) {
            throw refused(file + " is not UTF-8", ex);
        } catch (NoSuchFileException ex) {
            // Gone while it was read, as when the planner stops.
            throw notRunning(file, ex);
        } catch (IOException ex) {
            throw refused(file + " cannot be read: " + ex.getMessage(), ex);
        }
    }

    /**
     * Refuses a directory above the runtime directory that another account could change: one not
     * root's or this account's, or one others may write that is not sticky. A link of root or this
     * account is followed, as the window follows it.
     */
    private static void above(Path path, long own) {
        final Entry entry = entry(path, path);
        final long owner = entry.owner();
        final int mode = entry.mode();
        if (owner != 0 && owner != own) {
            throw refused(path + " belongs to another account");
        }
        if ((mode & TYPE) == LINK_TYPE) {
            return;
        }
        if ((mode & TYPE) != DIRECTORY_TYPE) {
            throw refused(path + " is not a directory");
        }
        if ((mode & OTHERS_WRITE) != 0 && (mode & STICKY) == 0) {
            throw refused(path + " may be changed by other accounts");
        }
    }

    /** Refuses a path unless it is of a type, no link, this account's and closed to all others. */
    private static void ownOnly(Path path, long own, int type, Path file) {
        final Entry entry = entry(path, file);
        final long owner = entry.owner();
        final int mode = entry.mode();
        if ((mode & TYPE) != type) {
            throw refused(path + " is not a " + (type == FILE_TYPE ? "file" : "directory"));
        }
        if (owner != own) {
            throw refused(path + " belongs to another account");
        }
        // The planner makes them owner-only; one others may open has leaked its token.
        if ((mode & 0x3f) != 0) {
            throw refused(path + " may be read or written by other accounts");
        }
    }

    /** A path's owner and mode, of a link itself. */
    private record Entry(long owner, int mode) {}

    private static Entry entry(Path path, Path file) {
        try {
            final Map<String, Object> read =
                    Files.readAttributes(path, "unix:uid,mode", LinkOption.NOFOLLOW_LINKS);
            if (read.get("uid") instanceof Number owner
                    && read.get("mode") instanceof Number mode) {
                return new Entry(owner.longValue(), mode.intValue());
            }
            throw refused(path + " names no owner or mode");
        } catch (NoSuchFileException ex) {
            throw notRunning(file, ex);
        } catch (IOException | UnsupportedOperationException ex) {
            throw refused(path + " cannot be read: " + ex.getMessage());
        }
    }

    private static Planner.Unavailable notRunning(Path file, Exception cause) {
        return new Planner.Unavailable(
                Planner.Reason.NOT_RUNNING, "no planner runs: " + file + " cannot be read", cause);
    }

    private static Planner.Unavailable refused(String why) {
        return refused(why, null);
    }

    private static Planner.Unavailable refused(String why, @Nullable Throwable cause) {
        return new Planner.Unavailable(
                Planner.Reason.ADDRESS_REFUSED, "the planner's address is refused: " + why, cause);
    }
}
