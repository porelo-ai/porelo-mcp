package org.fuin.porelo.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.fuin.porelo.wire.varlink.VarlinkServer;

/** A daemon for the tests: a real Varlink server on a Unix socket, serving a given description. */
final class StandInDaemon implements AutoCloseable {

    /** The real interface, as porelo served it at the commit named beside it. */
    static String tasks1() throws IOException {
        try (var in =
                StandInDaemon.class.getResourceAsStream(
                        "/varlink/org.fuin.porelo.Tasks1.varlink")) {
            if (in == null) {
                throw new IllegalStateException(
                        "The Tasks1 interface is not among the test resources");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The calls the stand-in received, as method and parameters. */
    final List<Map.Entry<String, Map<String, Object>>> calls = new CopyOnWriteArrayList<>();

    private final VarlinkServer server;
    private final Thread thread;
    private final Path socket;

    /** Serves a description, with handlers by method name without the interface. */
    StandInDaemon(Path directory, String description, Map<String, VarlinkServer.Method> methods) {
        this(directory, "org.fuin.porelo.Tasks1", description, "9.9.9-standin", methods);
    }

    /** Serves a description under an interface name, reporting a version. */
    StandInDaemon(
            Path directory,
            String interfaceName,
            String description,
            String version,
            Map<String, VarlinkServer.Method> methods) {
        socket = directory.resolve("daemon.sock");
        server =
                new VarlinkServer(socket, interfaceName)
                        .describedBy(description)
                        .reporting(version);
        for (Map.Entry<String, VarlinkServer.Method> method : methods.entrySet()) {
            final String name = method.getKey();
            final VarlinkServer.Method handler = method.getValue();
            server.method(
                    name,
                    (parameters, replies) -> {
                        calls.add(Map.entry("org.fuin.porelo.Tasks1." + name, parameters));
                        handler.call(parameters, replies);
                    });
        }
        thread = new Thread(server, "stand-in daemon");
        thread.setDaemon(true);
        thread.start();
    }

    Path socket() {
        return socket;
    }

    @Override
    public void close() throws InterruptedException {
        server.close();
        thread.join(5_000);
    }
}
