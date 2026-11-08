package org.fuin.porelo.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.fuin.porelo.wire.Json;

/**
 * MCP's stdio transport: one JSON-RPC message per line on standard input, each answer one line on
 * standard output, and nothing else written there.
 */
final class StdioLines {

    /** The most bytes one message has, as over HTTP. */
    static final int MAX_LINE = HttpRequestHead.MAX_BODY;

    private StdioLines() {}

    /**
     * Answers messages until standard input ends.
     *
     * @param session What answers them.
     * @param in Standard input.
     * @param out Standard output, for answers alone.
     * @throws IOException When standard input or output fails.
     */
    static void serve(McpSession session, InputStream in, PrintStream out) throws IOException {
        final ByteArrayOutputStream line = new ByteArrayOutputStream();
        boolean tooLong = false;
        int next;
        while ((next = in.read()) >= 0) {
            if (next != '\n') {
                if (line.size() < MAX_LINE) {
                    line.write(next);
                } else {
                    // The rest of the line is read away, never held.
                    tooLong = true;
                }
                continue;
            }
            if (tooLong) {
                answer(out, tooLong());
            } else {
                final String message = line.toString(StandardCharsets.UTF_8).strip();
                if (!message.isEmpty()) {
                    final String answered = session.handle(message);
                    if (answered != null) {
                        answer(out, answered);
                    }
                }
            }
            line.reset();
            tooLong = false;
        }
    }

    private static String tooLong() {
        final Map<String, Object> error = new java.util.LinkedHashMap<>();
        error.put("jsonrpc", "2.0");
        error.put("id", null);
        error.put(
                "error",
                Map.of(
                        "code",
                        McpSession.INVALID_REQUEST,
                        "message",
                        "A message is longer than " + MAX_LINE + " bytes"));
        return Json.write(error);
    }

    private static void answer(PrintStream out, String json) throws IOException {
        // Json.write escapes every line break inside a string, so one answer is one line.
        out.print(json);
        out.print('\n');
        out.flush();
        if (out.checkError()) {
            throw new IOException("Standard output is closed");
        }
    }
}
