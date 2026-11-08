package org.fuin.porelo.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The ids streams run under, and a cancel that comes a moment before its call. */
class StreamsRegistryTest {

    // Harm: 1 and 1.0 streaming as two calls a cancel of one cannot tell apart, or "1" refused
    // for a number's stream.
    @Test
    void oneAndOnePointZeroAreOneIdAndTheStringOneIsAnother() throws Exception {
        CountDownLatch end = new CountDownLatch(1);
        try (Streams streams = new Streams(json -> true)) {
            assertThat(streams.start(1, waitFor(end))).as("1").isTrue();
            assertThat(streams.start(new BigDecimal("1.0"), waitFor(end)))
                    .as("1.0, the same id")
                    .isFalse();
            assertThat(streams.start("1", waitFor(end))).as("\"1\", another id").isTrue();
            end.countDown();
        }
    }

    // Harm: a cancel that overtook its request on another connection lost, the stream running on.
    @Test
    void aCancelThatCameJustBeforeItsCallEndsItAtOnce() throws Exception {
        CountDownLatch cancelled = new CountDownLatch(1);
        try (Streams streams = new Streams(json -> true)) {
            streams.cancel(5);
            streams.start(5, cancellation -> cancellation.onCancel(cancelled::countDown));

            assertThat(cancelled.await(5, TimeUnit.SECONDS)).as("started cancelled").isTrue();
        }
    }

    private static java.util.function.Consumer<Cancellation> waitFor(CountDownLatch end) {
        return cancellation -> {
            cancellation.onCancel(end::countDown);
            try {
                end.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        };
    }
}
