package org.fuin.porelo.mcp;

import org.jspecify.annotations.Nullable;

/**
 * The person's running planner, as far as the tools reach it: its description and one signed REST
 * call. Proving the listener, signing the request and checking the answer's signature happen behind
 * it; what comes out is the planner's own answer.
 */
public interface Planner {

    /**
     * The planner's OpenAPI description, as {@code GET /openapi} answers it.
     *
     * @return The answer's body, a JSON text.
     * @throws Unavailable When the planner cannot be reached, proved or believed, or answers the
     *     description with a status other than 200.
     */
    String openApi();

    /**
     * Sends one request and returns the planner's answer, whatever its status.
     *
     * @param request The request.
     * @return The planner's answer, its signature checked.
     * @throws Unavailable When the planner cannot be reached, proved or believed.
     */
    Answer call(Request request);

    /**
     * One request to the planner.
     *
     * @param method The HTTP method, upper case.
     * @param target The raw request target as sent and signed: the path with every parameter filled
     *     in and percent-encoded, then {@code ?} and the raw query when there is one.
     * @param body The JSON body, or {@code null} for none.
     */
    record Request(String method, String target, @Nullable String body) {}

    /**
     * The planner's answer to a request.
     *
     * @param status The HTTP status.
     * @param contentType The {@code Content-Type} header, empty when absent.
     * @param body The body as UTF-8 text, empty when absent.
     */
    record Answer(int status, String contentType, String body) {}

    /** Why a call reached no answer of the planner's that can be believed. */
    enum Reason {
        /** No address file, or no planner listening where it names. */
        NOT_RUNNING,
        /** The address file, or a directory above it, could have been placed by another account. */
        ADDRESS_REFUSED,
        /** The listener did not prove that it holds the token. */
        PROOF_REFUSED,
        /** The planner answered 401 even after the address file was read again. */
        NOT_ACCEPTED,
        /** An answer's {@code Porelo-Answer} did not hold. */
        ANSWER_FORGED,
        /** The connection broke or timed out, or the answer was larger than a planner sends. */
        UNREACHABLE,
        /** The planner answered its description with a status other than 200. */
        NO_DESCRIPTION
    }

    /** A call that reached no answer of the planner's that can be believed. */
    final class Unavailable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** Why. */
        private final Reason reason;

        /**
         * Says why.
         *
         * @param reason Why, in one word.
         * @param message Why, in words a person reads, never holding the token.
         * @param cause What failed underneath, or {@code null}.
         */
        public Unavailable(Reason reason, String message, @Nullable Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        /**
         * Why, in one word.
         *
         * @return The reason.
         */
        public Reason reason() {
            return reason;
        }
    }
}
