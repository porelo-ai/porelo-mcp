package org.fuin.porelo.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The head of one HTTP/1.1 request, read within fixed bounds and strict framing.
 *
 * <p>Only what streamable HTTP needs is read, and anything a reader could frame otherwise than the
 * sender meant is refused rather than guessed at: a request smuggled past the token starts there.
 *
 * @param method The request's method.
 * @param target The request's target, such as {@code /mcp}.
 * @param headers Each header once, its name in lower case.
 */
record HttpRequestHead(String method, String target, Map<String, String> headers) {

    /** The most bytes a head has, its ending blank line included. */
    static final int MAX_HEAD = 16 * 1024;

    /** The most header lines a head has. */
    static final int MAX_LINES = 64;

    /** The most bytes a body has. */
    static final int MAX_BODY = 1024 * 1024;

    /** Headers that must not appear twice, since two readers could each take another one. */
    private static final java.util.Set<String> ONCE =
            java.util.Set.of(
                    "content-length",
                    "authorization",
                    "host",
                    "mcp-protocol-version",
                    "mcp-method",
                    "mcp-name");

    /** A request refused as malformed, with the status it gets. */
    static final class Refused extends Exception {

        private static final long serialVersionUID = 1L;

        private final int status;

        Refused(int status, String message) {
            super(message);
            this.status = status;
        }

        /** The HTTP status the refusal answers with. */
        int status() {
            return status;
        }
    }

    /** Copies the headers, so the head cannot change. */
    HttpRequestHead {
        headers = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

    /**
     * Reads a head up to and including its blank line, and nothing after it.
     *
     * @param in The connection.
     * @return The head.
     * @throws Refused When it breaks a bound or the framing.
     * @throws IOException When the connection fails or ends first.
     */
    static HttpRequestHead read(InputStream in) throws Refused, IOException {
        final ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        int previous = -1;
        while (matched < 4) {
            final int next = in.read();
            if (next < 0) {
                throw new IOException("The connection ended inside the request's head");
            }
            if (head.size() == MAX_HEAD) {
                throw new Refused(431, "The request's head is longer than " + MAX_HEAD + " bytes");
            }
            if (next == '\n' && previous != '\r') {
                // Refused at once: a head of bare LFs would otherwise be read to its bound.
                throw new Refused(400, "A line of the head does not end with CRLF");
            }
            head.write(next);
            previous = next;
            final int wanted = matched % 2 == 0 ? '\r' : '\n';
            matched = next == wanted ? matched + 1 : next == '\r' ? 1 : 0;
        }
        final String text = head.toString(StandardCharsets.ISO_8859_1);
        final String[] lines = text.substring(0, text.length() - 4).split("\r\n", -1);
        if (lines.length - 1 > MAX_LINES) {
            throw new Refused(431, "The request has more than " + MAX_LINES + " header lines");
        }
        for (final String line : lines) {
            if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
                throw new Refused(400, "A line of the head does not end with CRLF");
            }
        }
        final String[] request = lines[0].split(" ", -1);
        if (request.length != 3
                || request[0].isEmpty()
                || request[1].isEmpty()
                || !request[2].equals("HTTP/1.1")) {
            throw new Refused(400, "The request line is not 'METHOD target HTTP/1.1'");
        }
        final Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            final String line = lines[i];
            if (line.isEmpty() || line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                throw new Refused(400, "A folded or empty header line");
            }
            final int colon = line.indexOf(':');
            if (colon <= 0 || !isToken(line.substring(0, colon))) {
                throw new Refused(400, "A header name that is no token");
            }
            final String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
            final String value = value(line.substring(colon + 1));
            if (name.equals("transfer-encoding")) {
                throw new Refused(400, "Transfer-Encoding is not taken; send Content-Length");
            }
            if (headers.containsKey(name) && ONCE.contains(name)) {
                throw new Refused(400, "The header " + name + " appears twice");
            }
            headers.merge(name, value, (first, more) -> first + ", " + more);
        }
        return new HttpRequestHead(request[0], request[1], headers);
    }

    /**
     * Reads the body this head announces, after the head was accepted.
     *
     * @param in The connection, right after the head.
     * @return The body.
     * @throws Refused When the length is missing, not purely decimal, too large, or the body ends
     *     short of it.
     * @throws IOException When the connection fails.
     */
    byte[] body(InputStream in) throws Refused, IOException {
        final String length = header("content-length");
        if (length == null) {
            throw new Refused(400, "A POST needs Content-Length");
        }
        if (length.isEmpty()
                || length.length() > 7
                || !length.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new Refused(400, "Content-Length is not a plain decimal number");
        }
        final int bytes = Integer.parseInt(length);
        if (bytes > MAX_BODY) {
            throw new Refused(413, "The body is longer than " + MAX_BODY + " bytes");
        }
        final byte[] body = in.readNBytes(bytes);
        if (body.length < bytes) {
            throw new Refused(400, "The body ended before Content-Length did");
        }
        return body;
    }

    /**
     * A header's value.
     *
     * @param name Its name in lower case.
     * @return Its value, or {@code null} when it is absent.
     */
    @Nullable String header(String name) {
        return headers.get(name);
    }

    /**
     * A header's value without the spaces and tabs around it; any other control byte is refused,
     * since another reader would strip or keep it otherwise.
     */
    private static String value(String raw) throws Refused {
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            if (c < 0x20 && c != '\t' || c == 0x7f) {
                throw new Refused(400, "A header value holds a control byte");
            }
        }
        int start = 0;
        int end = raw.length();
        while (start < end && (raw.charAt(start) == ' ' || raw.charAt(start) == '\t')) {
            start++;
        }
        while (end > start && (raw.charAt(end - 1) == ' ' || raw.charAt(end - 1) == '\t')) {
            end--;
        }
        return raw.substring(start, end);
    }

    /** The method, the target and the headers' names: never a value, which may be the token. */
    @Override
    public String toString() {
        return "HttpRequestHead[" + method + " " + target + ", headers " + headers.keySet() + "]";
    }

    private static boolean isToken(String name) {
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            if (c <= 0x20 || c >= 0x7f || "()<>@,;:\\\"/[]?={}".indexOf(c) >= 0) {
                return false;
            }
        }
        return true;
    }
}
