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

    /**
     * The pinned Tasks1 with the named methods marked for agents, whatever the pin says of them:
     * for tests of the protocol, which need a streaming or writing tool, not of the markers.
     */
    static String tasks1Meant(String... names) throws IOException {
        String text = tasks1();
        for (String name : names) {
            String method = "\nmethod " + name + "(";
            int at = text.indexOf(method);
            if (at < 0) {
                throw new IllegalArgumentException("Tasks1 declares no method " + name);
            }
            int lineStart = text.lastIndexOf('\n', at - 1) + 1;
            String above = text.substring(lineStart, at);
            text =
                    above.startsWith("# " + AgentMarker.WORD + ":")
                            ? text.substring(0, lineStart) + "# x-mcp: true" + text.substring(at)
                            : text.substring(0, at) + "\n# x-mcp: true" + text.substring(at);
        }
        return text;
    }

    /** The methods a description marks for agents, read from its text alone. */
    static List<String> meantIn(String description) {
        List<String> meant = new java.util.ArrayList<>();
        java.util.regex.Matcher marked =
                java.util.regex.Pattern.compile("\n# x-mcp: true\nmethod (\\w+)\\(")
                        .matcher(description);
        while (marked.find()) {
            meant.add(marked.group(1));
        }
        return meant;
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
