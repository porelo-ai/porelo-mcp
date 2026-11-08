package org.fuin.porelo.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.jspecify.annotations.Nullable;

/**
 * The {@code porelo-mcp} command. Its arguments, exit codes and start order are the contract with
 * the daemon that starts it, written in {@code doc/binding.md}.
 */
public final class Main {

    /** Stopped on {@code SIGTERM} or {@code SIGINT}. */
    static final int STOPPED = 0;

    /** Failed after {@code ready}. */
    static final int FAILED = 1;

    /** Refused at start: wrong arguments, a refused token, or a socket place refused. */
    static final int REFUSED = 2;

    /** The daemon socket was not reachable, or its interface not readable, before {@code ready}. */
    static final int NO_DAEMON = 3;

    /** The interface whose methods are the tools. */
    static final String INTERFACE = "org.fuin.porelo.Tasks1";

    /** How long one connection may take, head and body. */
    static final Duration DEADLINE = Duration.ofSeconds(30);

    private Main() {}

    /**
     * Runs the command and exits with its code.
     *
     * @param args The arguments.
     */
    public static void main(String[] args) {
        System.exit(run(args, System.in, System.out, System.err));
    }

    /**
     * Runs the command.
     *
     * @param args The arguments.
     * @param in Standard input, where the token comes from.
     * @param out Standard output, where {@code ready} goes.
     * @param err Standard error, for every other word.
     * @return The exit code.
     */
    static int run(String[] args, InputStream in, PrintStream out, PrintStream err) {
        if (args.length == 0 || !args[0].equals("serve")) {
            err.println(
                    "porelo-mcp: usage: porelo-mcp serve --task <name> --socket <path> --daemon-socket <path>");
            return REFUSED;
        }
        final Map<String, String> options;
        try {
            options =
                    options(
                            List.of(args).subList(1, args.length),
                            Set.of("--task", "--socket", "--daemon-socket"));
        } catch (StartRefused ex) {
            err.println("porelo-mcp: " + ex.getMessage());
            return REFUSED;
        }
        final String task = java.util.Objects.requireNonNull(options.get("--task"));
        final Path socket = Path.of(java.util.Objects.requireNonNull(options.get("--socket")));
        final SocketDaemon daemon =
                new SocketDaemon(
                        Path.of(java.util.Objects.requireNonNull(options.get("--daemon-socket"))));
        final BearerToken token;
        try {
            token = BearerToken.read(in);
            in.close();
        } catch (StartRefused ex) {
            err.println("porelo-mcp: " + task + ": " + ex.getMessage());
            return REFUSED;
        } catch (IOException ex) {
            err.println(
                    "porelo-mcp: " + task + ": standard input cannot be read: " + ex.getMessage());
            return REFUSED;
        }
        final List<Tool> tools;
        try {
            tools = Tool.of(VarlinkIdl.parse(daemon.describe(INTERFACE)));
        } catch (VarlinkException | IllegalArgumentException ex) {
            err.println(
                    "porelo-mcp: "
                            + task
                            + ": the daemon at "
                            + daemon.socket()
                            + " gave no interface: "
                            + ex.getMessage());
            return NO_DAEMON;
        }
        final McpHttpServer server;
        try {
            server =
                    new McpHttpServer(
                            socket,
                            token,
                            new McpSession(tools, daemon, version()),
                            McpHttpServer.sameUserAs(SocketPlace.check(socket)),
                            DEADLINE);
        } catch (StartRefused ex) {
            err.println("porelo-mcp: " + task + ": " + ex.getMessage());
            return REFUSED;
        } catch (IOException ex) {
            err.println(
                    "porelo-mcp: "
                            + task
                            + ": the socket "
                            + socket
                            + " cannot be bound: "
                            + ex.getMessage());
            return REFUSED;
        }
        final java.util.concurrent.atomic.AtomicBoolean stopped =
                new java.util.concurrent.atomic.AtomicBoolean();
        final Runnable stop =
                () -> {
                    stopped.set(true);
                    server.close();
                };
        sun.misc.Signal.handle(new sun.misc.Signal("TERM"), signal -> stop.run());
        sun.misc.Signal.handle(new sun.misc.Signal("INT"), signal -> stop.run());
        out.println("ready");
        out.flush();
        try {
            server.serve();
        } catch (IOException ex) {
            err.println("porelo-mcp: " + task + ": serving failed: " + ex.getMessage());
            server.close();
            return FAILED;
        }
        return stopped.get() ? STOPPED : FAILED;
    }

    /** Reads {@code --name value} pairs, each of the given names exactly once. */
    static Map<String, String> options(List<String> given, Set<String> names) throws StartRefused {
        final Map<String, String> options = new HashMap<>();
        for (int i = 0; i < given.size(); i += 2) {
            final String name = given.get(i);
            if (!names.contains(name)) {
                throw new StartRefused("Unknown argument '" + name + "'");
            }
            if (i + 1 == given.size() || given.get(i + 1).isEmpty()) {
                throw new StartRefused(name + " needs a value");
            }
            if (options.put(name, given.get(i + 1)) != null) {
                throw new StartRefused(name + " is given twice");
            }
        }
        for (final String name : names) {
            if (!options.containsKey(name)) {
                throw new StartRefused(name + " is missing");
            }
        }
        return options;
    }

    /** This program's version, as the build wrote it. */
    static String version() {
        final Properties properties = new Properties();
        try (@Nullable InputStream in = Main.class.getResourceAsStream("porelo-mcp.properties")) {
            if (in == null) {
                throw new IllegalStateException("porelo-mcp.properties is not on the classpath");
            }
            properties.load(in);
        } catch (IOException ex) {
            throw new IllegalStateException("porelo-mcp.properties cannot be read", ex);
        }
        return properties.getProperty("version", "unknown");
    }
}
