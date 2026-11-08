package org.fuin.porelo.mcp;

import java.util.Map;
import java.util.Set;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.JsonException;
import org.jspecify.annotations.Nullable;

/**
 * Which MCP era an HTTP request is of, and whether its headers may be served: in the second era
 * ({@code 2026-07-28}) {@code MCP-Protocol-Version}, {@code Mcp-Method} and, for a call, {@code
 * Mcp-Name} say what the body says, since a proxy routes by them without reading the body.
 *
 * @param modern Whether the request is of the second era.
 * @param refusal The answer refusing it, with HTTP 400, or {@code null} to serve it.
 */
record Era(boolean modern, @Nullable String refusal) {

    /** MCP's code for a header that says something else than the body. */
    static final int HEADER_MISMATCH = -32020;

    /** The errors that refuse a modern request as such, which HTTP answers with 400. */
    private static final Set<Integer> REFUSING =
            Set.of(
                    McpSession.PARSE_ERROR,
                    McpSession.INVALID_REQUEST,
                    McpSession.INVALID_PARAMS,
                    HEADER_MISMATCH,
                    McpSession.UNSUPPORTED_PROTOCOL_VERSION);

    /**
     * Reads the era from the headers and the body.
     *
     * @param head The request's head.
     * @param message The request's body.
     * @return Its era, and the refusal if its headers cannot be served.
     */
    static Era of(HttpRequestHead head, String message) {
        final String version = head.header("mcp-protocol-version");
        final String method = head.header("mcp-method");
        final String name = head.header("mcp-name");
        @Nullable Map<?, ?> request = null;
        try {
            if (Json.parseDecimals(message) instanceof Map<?, ?> parsed) {
                request = parsed;
            }
        } catch (JsonException ex) {
            // Not JSON: a modern request's headers then name what the body does not say.
        }
        final @Nullable Map<?, ?> params =
                request != null && request.get("params") instanceof Map<?, ?> given ? given : null;
        final @Nullable Object bodyMethod = request == null ? null : request.get("method");
        // The same reading of the body as the session's, so the two never take it for two eras.
        final boolean modern =
                method != null
                        || name != null
                        || request != null && McpSession.isModern(params)
                        || version != null && McpSession.MODERN_VERSIONS.contains(version);
        if (!modern) {
            if (version != null && !McpSession.VERSIONS.contains(version)) {
                return new Era(
                        false,
                        McpHttpServer.jsonRpcError(
                                null,
                                McpSession.INVALID_REQUEST,
                                "This server speaks MCP "
                                        + String.join(", ", McpSession.supportedVersions())));
            }
            return new Era(false, null);
        }
        if (request == null) {
            // A body that is no object - a batch, a value, not JSON - says no method at all.
            return mismatch(null, "Mcp-Method", method, null);
        }
        final Object given = request.get("id");
        final @Nullable Object id =
                given instanceof String || given instanceof Number ? given : null;
        if (method == null || !method.equals(bodyMethod)) {
            return mismatch(id, "Mcp-Method", method, bodyMethod);
        }
        final @Nullable Object metaVersion =
                params != null && params.get("_meta") instanceof Map<?, ?> meta
                        ? meta.get(McpSession.PROTOCOL_VERSION)
                        : null;
        if (version == null) {
            return mismatch(id, "MCP-Protocol-Version", null, metaVersion);
        }
        if (request.containsKey("id")) {
            if (!version.equals(metaVersion)) {
                return mismatch(id, "MCP-Protocol-Version", version, metaVersion);
            }
        } else {
            // A notification need not name its version in _meta; if it does, it says the same.
            if (metaVersion != null && !version.equals(metaVersion)) {
                return mismatch(null, "MCP-Protocol-Version", version, metaVersion);
            }
            if (!McpSession.MODERN_VERSIONS.contains(version)) {
                return new Era(true, McpSession.unsupported(null, version));
            }
        }
        final @Nullable Object bodyName = params == null ? null : params.get("name");
        if ("tools/call".equals(bodyMethod)) {
            if (name == null || !name.equals(bodyName)) {
                return mismatch(id, "Mcp-Name", name, bodyName);
            }
        } else if (name != null) {
            return mismatch(id, "Mcp-Name", name, null);
        }
        return new Era(true, null);
    }

    /**
     * Tells whether an answer refuses the request as such, rather than answering it.
     *
     * @param answer The session's answer.
     * @return {@code true} for an error of {@link #REFUSING}.
     */
    static boolean refusesTheRequest(String answer) {
        return Json.parseDecimals(answer) instanceof Map<?, ?> response
                && response.get("error") instanceof Map<?, ?> error
                && error.get("code") instanceof Number code
                && REFUSING.contains(code.intValue());
    }

    private static Era mismatch(
            @Nullable Object id, String header, @Nullable String said, @Nullable Object body) {
        return new Era(
                true,
                McpHttpServer.jsonRpcError(
                        id,
                        HEADER_MISMATCH,
                        "The "
                                + header
                                + " header says "
                                + (said == null ? "nothing" : "'" + said + "'")
                                + ", the body "
                                + (body == null ? "nothing" : "'" + body + "'")));
    }
}
