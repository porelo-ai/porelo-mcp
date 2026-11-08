package org.fuin.porelo.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.wire.varlink.VarlinkException;

/**
 * The daemon's sets for a person's own client, by method name without the interface: what reads,
 * what the person allowed to write, what is never offered, and what stands for the person's own
 * judgement.
 *
 * @param reading Porelo's reading methods.
 * @param writing The person's own list, minus {@code never}, as the daemon says.
 * @param never Porelo's methods that are never offered.
 * @param judgement Porelo's methods that stand for the person's own judgement.
 */
public record McpMethods(
        Set<String> reading, Set<String> writing, Set<String> never, Set<String> judgement) {

    /** The method the daemon names its sets with. */
    static final String METHOD = "org.fuin.porelo.Tasks1.McpMethods";

    /** No set at all: what a daemon without {@code McpMethods} gets. */
    static final McpMethods NONE = new McpMethods(Set.of(), Set.of(), Set.of(), Set.of());

    /** Copies the sets, so they cannot change. */
    public McpMethods {
        reading = Set.copyOf(reading);
        writing = Set.copyOf(writing);
        never = Set.copyOf(never);
        judgement = Set.copyOf(judgement);
    }

    /**
     * Asks the daemon for its sets.
     *
     * @param daemon The person's own daemon.
     * @return The sets.
     * @throws VarlinkException With an error name when the daemon refused, among them its {@code
     *     MethodNotFound} when it has no {@code McpMethods}; without one when it was not reached.
     * @throws IllegalArgumentException When a set is missing or holds something but names.
     */
    public static McpMethods of(Daemon daemon) {
        final Map<String, Object> reply = daemon.call(METHOD, Map.of());
        return new McpMethods(
                names(reply, "reading"),
                names(reply, "writing"),
                names(reply, "never"),
                names(reply, "judgement"));
    }

    /**
     * The tools a person's own client is offered: those reading or writing, never one in {@code
     * never}, whatever the other sets say.
     *
     * @param tools Every tool of the interface.
     * @return The offered ones, in the interface's order.
     */
    public List<Tool> offered(List<Tool> tools) {
        final List<Tool> offered = new ArrayList<>();
        for (final Tool tool : tools) {
            final boolean named = reading.contains(tool.name()) || writing.contains(tool.name());
            // Refused here too, so a fault in the daemon's writing set alone opens nothing.
            if (named && !never.contains(tool.name())) {
                offered.add(tool);
            }
        }
        return List.copyOf(offered);
    }

    private static Set<String> names(Map<String, Object> reply, String set) {
        if (!(reply.get(set) instanceof List<?> listed)) {
            throw new IllegalArgumentException("McpMethods answered no list '" + set + "'");
        }
        final List<String> names = new ArrayList<>();
        for (final Object name : listed) {
            if (!(name instanceof String text)) {
                throw new IllegalArgumentException("McpMethods' '" + set + "' holds a non-name");
            }
            names.add(text);
        }
        return Set.copyOf(names);
    }
}
