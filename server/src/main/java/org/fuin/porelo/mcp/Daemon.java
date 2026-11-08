package org.fuin.porelo.mcp;

import java.util.Map;
import java.util.function.Consumer;

/** The daemon, as far as the tools reach it: one call of one method, once or as a stream. */
@FunctionalInterface
public interface Daemon {

    /**
     * Calls a method once.
     *
     * @param method The fully qualified method name.
     * @param parameters The parameters, passed on unchanged.
     * @return The reply's parameters.
     * @throws org.fuin.porelo.wire.varlink.VarlinkException With an error name when the daemon
     *     refused, without one when it could not be reached or the connection broke.
     */
    Map<String, Object> call(String method, Map<String, Object> parameters);

    /**
     * Calls a method with {@code more}, handing each reply on as it comes, the last one too. A
     * method that answers once gives one reply. A daemon that cannot stream answers once.
     *
     * @param method The fully qualified method name.
     * @param parameters The parameters, passed on unchanged.
     * @param each Receives each reply's parameters as it comes.
     * @param cancellation Ends the call when cancelled, the connection with it.
     * @return The last reply's parameters; after a cancel, whatever came last, if anything.
     * @throws org.fuin.porelo.wire.varlink.VarlinkException As {@link #call}, and when a cancel
     *     ended the connection.
     */
    default Map<String, Object> stream(
            String method,
            Map<String, Object> parameters,
            Consumer<Map<String, Object>> each,
            Cancellation cancellation) {
        final Map<String, Object> reply = call(method, parameters);
        each.accept(reply);
        return reply;
    }
}
