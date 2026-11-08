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
        System.exit(run(args, System.in, System.out, System.err, System.getenv()));
    }

    /**
     * Runs the command.
     *
     * @param args The arguments.
     * @param in Standard input, where the token comes from.
     * @param out Standard output, where {@code ready} goes.
     * @param err Standard error, for every other word.
     * @param environment The environment, where the daemon's default socket comes from.
     * @return The exit code.
     */
    static int run(
            String[] args,
            InputStream in,
            PrintStream out,
            PrintStream err,
            Map<String, String> environment) {
        if (args.length > 0 && args[0].equals("--check")) {
            return check(List.of(args).subList(1, args.length), out, err, environment);
        }
        if (args.length > 0 && args[0].equals("stdio")) {
            return stdio(List.of(args).subList(1, args.length), in, out, err, environment);
        }
        if (args.length == 0 || !args[0].equals("serve")) {
            err.println(
                    "porelo-mcp: usage: porelo-mcp serve --task <name> --socket <path> --daemon-socket <path>");
            err.println("       porelo-mcp stdio [--daemon-socket <path>]");
            err.println("       porelo-mcp --check [--daemon-socket <path>]");
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
        final Path socket;
        final SocketDaemon daemon;
        try {
            socket = path("--socket", options.get("--socket"));
            daemon = new SocketDaemon(path("--daemon-socket", options.get("--daemon-socket")));
        } catch (StartRefused ex) {
            err.println("porelo-mcp: " + task + ": " + ex.getMessage());
            return REFUSED;
        }
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
            tools = toolsOf(daemon.describe(INTERFACE));
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
        // Handled from here on, so a stop while binding still removes the path and exits 0.
        final java.util.concurrent.atomic.AtomicBoolean stopped =
                new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicReference<@Nullable McpHttpServer> bound =
                new java.util.concurrent.atomic.AtomicReference<>();
        final Runnable stop =
                () -> {
                    stopped.set(true);
                    final McpHttpServer running = bound.get();
                    if (running != null) {
                        running.close();
                    }
                };
        sun.misc.Signal.handle(new sun.misc.Signal("TERM"), signal -> stop.run());
        sun.misc.Signal.handle(new sun.misc.Signal("INT"), signal -> stop.run());
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
        bound.set(server);
        if (stopped.get()) {
            // Stopped while binding: no ready, and the path is removed.
            server.close();
            return STOPPED;
        }
        out.println("ready");
        out.flush();
        try {
            server.serve();
        } catch (IOException ex) {
            err.println("porelo-mcp: " + task + ": serving failed: " + ex.getMessage());
            return FAILED;
        } finally {
            // On this thread too, so the process cannot exit before the path is gone.
            server.close();
        }
        return stopped.get() ? STOPPED : FAILED;
    }

    /**
     * Says whether the daemon is reached: its version, then each method offered as a tool, one per
     * line.
     */
    static int check(
            List<String> args, PrintStream out, PrintStream err, Map<String, String> environment) {
        final Path socket;
        try {
            socket = personsSocket(args, environment);
        } catch (StartRefused ex) {
            err.println("porelo-mcp: " + ex.getMessage());
            return ex instanceof NoDaemonSocket ? NO_DAEMON : REFUSED;
        }
        final SocketDaemon daemon = new SocketDaemon(socket);
        final Object version;
        try {
            version = daemon.call("org.varlink.service.GetInfo", Map.of()).get("version");
            if (!(version instanceof String named) || named.isBlank()) {
                err.println("porelo-mcp: GetInfo on " + socket + " named no version");
                return NO_DAEMON;
            }
        } catch (VarlinkException ex) {
            err.println(
                    "porelo-mcp: GetInfo on " + socket + " was not answered: " + ex.getMessage());
            return NO_DAEMON;
        }
        final List<Tool> tools;
        try {
            tools = toolsOf(daemon.describe(INTERFACE));
        } catch (VarlinkException ex) {
            err.println(
                    "porelo-mcp: GetInterfaceDescription of "
                            + INTERFACE
                            + " on "
                            + socket
                            + " was not answered: "
                            + ex.getMessage());
            return NO_DAEMON;
        } catch (IllegalArgumentException ex) {
            err.println(
                    "porelo-mcp: the description of "
                            + INTERFACE
                            + " on "
                            + socket
                            + " does not parse: "
                            + ex.getMessage());
            return NO_DAEMON;
        }
        out.println("porelo-mcp reaches porelod " + version + " at " + socket);
        for (final Tool tool : tools) {
            out.println(tool.name());
        }
        out.flush();
        return STOPPED;
    }

    /**
     * Serves a person's own MCP client over standard input and output, one JSON-RPC message per
     * line, with only the tools the daemon's sets allow; nothing but protocol goes to standard
     * output.
     */
    static int stdio(
            List<String> args,
            InputStream in,
            PrintStream out,
            PrintStream err,
            Map<String, String> environment) {
        final Path socket;
        try {
            socket = personsSocket(args, environment);
        } catch (StartRefused ex) {
            err.println("porelo-mcp: " + ex.getMessage());
            return ex instanceof NoDaemonSocket ? NO_DAEMON : REFUSED;
        }
        final SocketDaemon daemon = new SocketDaemon(socket);
        final List<Tool> tools;
        try {
            tools = toolsOf(daemon.describe(INTERFACE));
        } catch (VarlinkException | IllegalArgumentException ex) {
            err.println(
                    "porelo-mcp: the daemon at "
                            + socket
                            + " gave no interface: "
                            + ex.getMessage());
            return NO_DAEMON;
        }
        McpMethods sets;
        try {
            sets = McpMethods.of(daemon);
        } catch (VarlinkException ex) {
            if (ex.getErrorName() == null) {
                err.println(
                        "porelo-mcp: the daemon at "
                                + socket
                                + " was not reached: "
                                + ex.getMessage());
                return NO_DAEMON;
            }
            err.println(
                    "porelo-mcp: no tools offered: the daemon answered McpMethods with "
                            + ex.getErrorName());
            sets = McpMethods.NONE;
        } catch (IllegalArgumentException ex) {
            err.println("porelo-mcp: no tools offered: " + ex.getMessage());
            sets = McpMethods.NONE;
        }
        final List<Tool> offered = sets.offered(tools);
        for (final Tool tool : offered) {
            if (sets.judgement().contains(tool.name())) {
                err.println(
                        "porelo-mcp: your client may call "
                                + tool.name()
                                + ", which stands for your own judgement, on your behalf");
            }
        }
        err.flush();
        final McpSession session = new McpSession(offered, daemon, version());
        try {
            StdioLines.serve(session, in, out);
        } catch (IOException ex) {
            err.println("porelo-mcp: standard input or output failed: " + ex.getMessage());
            return FAILED;
        }
        return STOPPED;
    }

    /** No socket is named and none can be derived: the daemon cannot be reached. */
    static final class NoDaemonSocket extends StartRefused {

        private static final long serialVersionUID = 1L;

        NoDaemonSocket(String message) {
            super(message);
        }
    }

    /** The person's own daemon socket: as given, or by default under {@code XDG_RUNTIME_DIR}. */
    static Path personsSocket(List<String> args, Map<String, String> environment)
            throws StartRefused {
        if (!args.isEmpty()) {
            return path(
                    "--daemon-socket",
                    options(args, Set.of("--daemon-socket")).get("--daemon-socket"));
        }
        final String runtime = environment.get("XDG_RUNTIME_DIR");
        if (runtime == null || runtime.isEmpty()) {
            throw new NoDaemonSocket(
                    "XDG_RUNTIME_DIR is unset or empty, so the daemon's socket is unknown;"
                            + " name it with --daemon-socket");
        }
        try {
            return Path.of(runtime, "porelo", "porelod.sock");
        } catch (java.nio.file.InvalidPathException ex) {
            throw new NoDaemonSocket("XDG_RUNTIME_DIR is no usable path: " + ex.getMessage());
        }
    }

    /** An option's value as a path, refused when it is none. */
    static Path path(String option, @Nullable String value) throws StartRefused {
        try {
            return Path.of(java.util.Objects.requireNonNull(value));
        } catch (java.nio.file.InvalidPathException ex) {
            throw new StartRefused(option + " is no usable path: " + ex.getReason());
        }
    }

    /**
     * The tools of a description, which must be of {@value #INTERFACE}: another interface's methods
     * are not the daemon's tools, whatever they are called.
     */
    static List<Tool> toolsOf(String description) {
        final VarlinkInterface described = VarlinkIdl.parse(description);
        if (!described.name().equals(INTERFACE)) {
            throw new IllegalArgumentException(
                    "it describes " + described.name() + ", not " + INTERFACE);
        }
        return Tool.of(described);
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
