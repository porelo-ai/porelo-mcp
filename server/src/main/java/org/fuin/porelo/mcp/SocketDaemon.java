package org.fuin.porelo.mcp;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
        final VarlinkClient client;
        try {
            client = new VarlinkClient(socket);
        } catch (VarlinkException ex) {
            throw broken(ex);
        }
        final Map<String, Object> reply;
        try {
            reply = client.call(method, parameters);
        } catch (VarlinkException ex) {
            closeQuietly(client);
            throw ex.getErrorName() == null ? broken(ex) : ex;
        } catch (RuntimeException ex) {
            // A frame that is no JSON, from a daemon that broke mid-answer.
            closeQuietly(client);
            throw broken(ex);
        }
        // The reply has arrived; failing to close the connection afterwards cannot take it back.
        closeQuietly(client);
        return reply;
    }

    @Override
    public Map<String, Object> stream(
            String method,
            Map<String, Object> parameters,
            Consumer<Map<String, Object>> each,
            Cancellation cancellation) {
        final VarlinkClient client;
        try {
            client = new VarlinkClient(socket);
        } catch (VarlinkException ex) {
            throw broken(ex);
        }
        // A cancel closes the connection, which ends a read that waits for the next change.
        cancellation.onCancel(() -> closeQuietly(client));
        final AtomicReference<Map<String, Object>> last = new AtomicReference<>(Map.of());
        try {
            client.callMore(
                    method,
                    parameters,
                    reply -> {
                        if (cancellation.cancelled()) {
                            return false;
                        }
                        last.set(reply);
                        each.accept(reply);
                        return true;
                    });
        } catch (VarlinkException ex) {
            throw ex.getErrorName() == null ? broken(ex) : ex;
        } catch (RuntimeException ex) {
            throw broken(ex);
        } finally {
            closeQuietly(client);
        }
        final Map<String, Object> got = last.get();
        return got == null ? Map.of() : got;
    }

    /** A connection that broke, as a failure without an error name that names the socket. */
    private VarlinkException broken(RuntimeException cause) {
        final String message = String.valueOf(cause.getMessage());
        return new VarlinkException(
                message.contains(socket.toString())
                        ? message
                        : "The daemon at " + socket + ": " + message,
                cause);
    }

    private static void closeQuietly(VarlinkClient client) {
        try {
            client.close();
        } catch (IOException ex) {
            // Nothing is left to read from it.
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
