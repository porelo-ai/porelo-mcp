package org.fuin.porelo.mcp;

import org.jspecify.annotations.Nullable;

/**
 * Whether a streamed call was cancelled, and what ends it then: the daemon connection it reads,
 * which a stream that sends only on a change would otherwise hold until its next reply.
 */
final class Cancellation {

    private boolean cancelled;
    private @Nullable Runnable onCancel;

    /**
     * Names what ends the call; run at once when it is cancelled already.
     *
     * @param action What ends it.
     */
    void onCancel(Runnable action) {
        final boolean now;
        synchronized (this) {
            onCancel = action;
            now = cancelled;
        }
        if (now) {
            action.run();
        }
    }

    /** Cancels the call, once; later calls do nothing. */
    void cancel() {
        final @Nullable Runnable action;
        synchronized (this) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            action = onCancel;
        }
        if (action != null) {
            action.run();
        }
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
