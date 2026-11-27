package org.fuin.porelo.mcp;

/**
 * Whether a method of the daemon's contract is meant for agents, as the last line of the comment
 * above it says: exactly {@code x-mcp: true}, or {@code x-mcp: false <reason>} with a reason of 1
 * to 200 characters. The word stands nowhere else in the comment.
 *
 * @param kind What the comment says.
 * @param description The comment without its marker line.
 * @param why For a refused or missing marker, what is wrong; for {@code false}, its reason.
 */
record AgentMarker(AgentMarker.Kind kind, String description, String why) {

    /** The marker's word. */
    static final String WORD = "x-mcp";

    /** The longest reason a {@code false} marker carries. */
    static final int MOST_REASON = 200;

    private static final String TRUE = WORD + ": true";

    private static final String FALSE = WORD + ": false ";

    /** What a method's comment says of agents. */
    enum Kind {
        /** Meant for agents: a tool. */
        MEANT,
        /** Not meant for agents, with its reason: no tool. */
        NOT_MEANT,
        /** No marker: no tool, and named. */
        MISSING,
        /** A marker not of the agreed form: no tool, and named. */
        MALFORMED
    }

    /**
     * Reads a method's comment.
     *
     * @param comment The comment, its lines stripped, as the description parser keeps it.
     * @return What it says.
     */
    static AgentMarker of(String comment) {
        final int cut = comment.lastIndexOf('\n');
        final String last = comment.substring(cut + 1);
        final String rest = cut < 0 ? "" : comment.substring(0, cut).strip();
        if (!last.startsWith(WORD)) {
            return comment.contains(WORD)
                    ? new AgentMarker(Kind.MALFORMED, comment, "its marker is not its last line")
                    : new AgentMarker(Kind.MISSING, comment, "it carries no marker");
        }
        if (rest.contains(WORD)) {
            return new AgentMarker(Kind.MALFORMED, comment, "it carries a second marker");
        }
        if (last.equals(TRUE)) {
            return new AgentMarker(Kind.MEANT, rest, "");
        }
        if (last.startsWith(FALSE)) {
            final String reason = last.substring(FALSE.length());
            if (!reason.isBlank()
                    && reason.equals(reason.strip())
                    && reason.length() <= MOST_REASON) {
                return new AgentMarker(Kind.NOT_MEANT, rest, reason);
            }
            return new AgentMarker(
                    Kind.MALFORMED, comment, "false carries no reason of 1 to 200 characters");
        }
        return new AgentMarker(Kind.MALFORMED, comment, "'" + last + "' is no marker");
    }
}
