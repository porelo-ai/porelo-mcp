package org.fuin.porelo.mcp;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.wire.varlink.VarlinkException;

/**
 * The daemon's hand-kept set of methods never offered to a person's own client, by method name
 * without the interface. It stands beside the markers, so a wrong marker alone opens nothing.
 *
 * @param never Porelo's methods that are never offered.
 */
public record McpMethods(Set<String> never) {

    /** The method the daemon names the set with. */
    static final String METHOD = "org.fuin.porelo.Tasks1.McpMethods";

    /** Copies the set, so it cannot change. */
    public McpMethods {
        never = Set.copyOf(never);
    }

    /**
     * Asks the daemon for its set.
     *
     * @param daemon The person's own daemon.
     * @return The set.
     * @throws VarlinkException With an error name when the daemon refused, among them its {@code
     *     MethodNotFound} when it has no {@code McpMethods}; without one when it was not reached.
     * @throws IllegalArgumentException When the set is missing or holds something but names.
     */
    public static McpMethods of(Daemon daemon) {
        final Map<String, Object> reply = daemon.call(METHOD, Map.of());
        if (!(reply.get("never") instanceof List<?> listed)) {
            throw new IllegalArgumentException("McpMethods answered no list 'never'");
        }
        final List<String> names = new ArrayList<>();
        for (final Object name : listed) {
            if (!(name instanceof String text)) {
                throw new IllegalArgumentException("McpMethods' 'never' holds a non-name");
            }
            names.add(text);
        }
        return new McpMethods(Set.copyOf(names));
    }

    /**
     * The tools a person's own client is offered: every tool meant for agents but those in {@code
     * never}. A tool in {@code never} is a marker that contradicts the daemon's own set, and is
     * named.
     *
     * @param tools The tools meant for agents.
     * @param err Where a contradiction is named.
     * @return The offered ones, in the interface's order.
     */
    public List<Tool> offered(List<Tool> tools, PrintStream err) {
        final List<Tool> offered = new ArrayList<>();
        for (final Tool tool : tools) {
            if (never.contains(tool.name())) {
                err.println(
                        "porelo-mcp: "
                                + tool.name()
                                + " is marked for agents, yet the daemon never offers it: no tool");
            } else {
                offered.add(tool);
            }
        }
        return List.copyOf(offered);
    }
}
