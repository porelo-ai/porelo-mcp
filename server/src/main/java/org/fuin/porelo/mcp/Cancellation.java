package org.fuin.porelo.mcp;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether a streamed call was cancelled, and what ends it then: the daemon connection it reads,
 * which a stream that sends only on a change would otherwise hold until its next reply, and over
 * HTTP the client's connection, which a write the client no longer reads would otherwise hold.
 */
final class Cancellation {

    private boolean cancelled;
    private final List<Runnable> onCancel = new ArrayList<>();

    /**
     * Adds what ends the call; run at once when it is cancelled already.
     *
     * @param action What ends it.
     */
    void onCancel(Runnable action) {
        final boolean now;
        synchronized (this) {
            now = cancelled;
            if (!now) {
                onCancel.add(action);
            }
        }
        if (now) {
            action.run();
        }
    }

    /** Cancels the call, once, running every action added; later calls do nothing. */
    void cancel() {
        final List<Runnable> actions;
        synchronized (this) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            actions = List.copyOf(onCancel);
            onCancel.clear();
        }
        // Outside the lock: an action may block a moment, closing a socket.
        actions.forEach(Runnable::run);
    }

    /**
     * Tells whether the call was cancelled.
     *
     * @return {@code true} once {@link #cancel()} ran.
     */
    synchronized boolean cancelled() {
        return cancelled;
    }
}
