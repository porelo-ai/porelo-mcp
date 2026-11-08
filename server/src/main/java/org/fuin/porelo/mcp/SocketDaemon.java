package org.fuin.porelo.mcp;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;

/**
 * The daemon on its Varlink socket, a connection per call, so a daemon restarted between two calls
 * is reached again.
 */
public final class SocketDaemon implements Daemon {

    private final Path socket;

    /**
     * Names the socket; nothing is connected yet.
     *
     * @param socket The daemon's socket.
     */
    public SocketDaemon(Path socket) {
        this.socket = socket;
    }

    /** The daemon's socket. */
    public Path socket() {
        return socket;
    }

    @Override
    public Map<String, Object> call(String method, Map<String, Object> parameters) {
        try (VarlinkClient client = new VarlinkClient(socket)) {
            return client.call(method, parameters);
        } catch (IOException ex) {
            // Closing a connection whose reply already arrived cannot lose that reply.
            throw new VarlinkException("Closing the connection to " + socket + " failed", ex);
        }
    }

    /**
     * Reads the description of one interface the daemon serves.
     *
     * @param interfaceName The interface's fully qualified name.
     * @return Its description, as served.
     * @throws VarlinkException When the daemon cannot be reached or does not describe it.
     */
    public String describe(String interfaceName) {
        final Object description =
                call(
                                "org.varlink.service.GetInterfaceDescription",
                                Map.of("interface", interfaceName))
                        .get("description");
        if (description instanceof String text) {
            return text;
        }
        throw new VarlinkException(
                "GetInterfaceDescription on " + socket + " answered no description");
    }
}
