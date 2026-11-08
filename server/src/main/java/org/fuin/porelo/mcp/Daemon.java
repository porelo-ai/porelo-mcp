package org.fuin.porelo.mcp;

import java.util.Map;

/** The daemon, as far as the tools reach it: one call of one method. */
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
}
