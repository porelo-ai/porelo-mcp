package org.fuin.porelo.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.jspecify.annotations.Nullable;

/**
 * The token a request must carry, kept only as its SHA-256 digest once read.
 *
 * <p>Digests are compared, so the comparison takes the same time wherever, and whether, the two
 * tokens' lengths differ.
 */
public final class BearerToken {

    /** The fewest bytes a token has. */
    static final int MIN_LENGTH = 32;

    /** The most bytes read for the token, its ending {@code \n} included. */
    static final int MAX_READ = 1024;

    private static final String SCHEME = "Bearer ";

    private final byte[] digest;

    private BearerToken(byte[] digest) {
        this.digest = digest;
    }

    /**
     * Reads the token: one line of 32 to 1023 printable ASCII bytes, ended by {@code \n}.
     *
     * @param in Where the daemon wrote it.
     * @return The token.
     * @throws StartRefused When the line is missing, unended, too short, too long or holds another
     *     byte; the message never holds what was read.
     * @throws IOException When the input cannot be read.
     */
    public static BearerToken read(InputStream in) throws StartRefused, IOException {
        final byte[] line = new byte[MAX_READ];
        try {
            return new BearerToken(sha256(line, readLine(in, line)));
        } finally {
            // Whatever was read, accepted or not, does not stay in memory.
            java.util.Arrays.fill(line, (byte) 0);
        }
    }

    private static int readLine(InputStream in, byte[] line) throws StartRefused, IOException {
        int length = 0;
        while (true) {
            final int next = in.read();
            if (next < 0) {
                throw new StartRefused("The token ended before its line did");
            }
            if (next == '\n') {
                break;
            }
            if (length == MAX_READ - 1) {
                throw new StartRefused("The token is longer than " + (MAX_READ - 1) + " bytes");
            }
            if (next < 0x21 || next > 0x7e) {
                throw new StartRefused("The token holds a byte that is no printable ASCII");
            }
            line[length++] = (byte) next;
        }
        if (length < MIN_LENGTH) {
            throw new StartRefused("The token is shorter than " + MIN_LENGTH + " bytes");
        }
        return length;
    }

    /**
     * Whether a request's {@code Authorization} header carries this token.
     *
     * @param authorization The header's value, or {@code null} when there is none.
     * @return Whether it is {@code Bearer} and this token.
     */
    public boolean accepts(@Nullable String authorization) {
        if (authorization == null || !authorization.startsWith(SCHEME)) {
            return false;
        }
        final byte[] presented =
                authorization.substring(SCHEME.length()).getBytes(StandardCharsets.UTF_8);
        try {
            return MessageDigest.isEqual(digest, sha256(presented, presented.length));
        } finally {
            java.util.Arrays.fill(presented, (byte) 0);
        }
    }

    @Override
    public String toString() {
        return "BearerToken[not shown]";
    }

    private static byte[] sha256(byte[] bytes, int length) {
        try {
            final MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(bytes, 0, length);
            return sha256.digest();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Every JDK has SHA-256", ex);
        }
    }
}
