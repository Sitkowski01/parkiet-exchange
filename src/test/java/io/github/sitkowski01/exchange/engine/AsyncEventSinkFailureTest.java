package io.github.sitkowski01.exchange.engine;

import io.github.sitkowski01.exchange.engine.SinkFixtures.Recorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.sitkowski01.exchange.engine.SinkFixtures.await;
import static io.github.sitkowski01.exchange.engine.SinkFixtures.event;
import static io.github.sitkowski01.exchange.engine.SinkFixtures.sleep;
import static org.assertj.core.api.Assertions.assertThat;

/** Awarie odbiorcy, backpressure i zamykanie z martwa baza. */
@Timeout(10)
class AsyncEventSinkFailureTest {

    private static final Duration BACKOFF = Duration.ofMillis(1);

    private final Recorder recorder = new Recorder();
    private final EventSink dead = batch -> {
        throw new IllegalStateException("database down");
    };

    @Test
    void failedWriteIsRetriedWithoutLossOrDuplicates() {
        AtomicInteger failuresLeft = new AtomicInteger(3);
        EventSink flaky = batch -> {
            if (failuresLeft.getAndDecrement() > 0) {
                throw new IllegalStateException("database down");
            }
            recorder.publish(batch);
        };
        AsyncEventSink sink = AsyncEventSink.start(flaky, 100, BACKOFF, Duration.ofSeconds(1));
        sink.publish(List.of(event(1), event(2)));
        sink.close();

        assertThat(recorder.calls).containsExactly(List.of(1L, 2L));
    }

    @Test
    void retriesBackOffInsteadOfSpinning() {
        AtomicInteger attempts = new AtomicInteger();
        AsyncEventSink sink = AsyncEventSink.start(batch -> {
            attempts.incrementAndGet();
            dead.publish(batch);
        }, 10, Duration.ofMillis(20), Duration.ofMillis(300));
        sink.publish(List.of(event(1)));
        sink.close();

        // Przerwy 20, 40, 80, 160 ms mieszcza w 300 ms ok. 5 prob. Bez przerw bylyby ich tysiace.
        assertThat(attempts.get()).isBetween(2, 8);
    }

    @Test
    void fullQueueBlocksPublisher() throws InterruptedException {
        CountDownLatch writerBusy = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AsyncEventSink sink = AsyncEventSink.start(batch -> {
            writerBusy.countDown();
            await(release);
        }, 1, BACKOFF, Duration.ofSeconds(1));
        sink.publish(List.of(event(1)));
        assertThat(writerBusy.await(5, TimeUnit.SECONDS)).isTrue();
        sink.publish(List.of(event(2)));

        Thread publisher = Thread.ofPlatform().start(() -> sink.publish(List.of(event(3))));
        publisher.join(200);
        assertThat(publisher.isAlive()).as("czeka na miejsce w kolejce").isTrue();

        release.countDown();
        publisher.join();
        sink.close();
    }

    /** Scenariusz z review: silnik wisi w publish na pelnej kolejce, a baza nie wraca. */
    @Test
    void shutdownClockReleasesPublisherStuckOnFullQueue() {
        AsyncEventSink sink = AsyncEventSink.start(dead, 1, BACKOFF, Duration.ofMillis(200));
        sink.publish(List.of(event(1)));
        sink.publish(List.of(event(2)));
        CompletableFuture<Void> stuck = CompletableFuture.runAsync(() -> sink.publish(List.of(event(3))));
        sleep(100);
        assertThat(stuck).isNotDone();

        sink.beginShutdown();

        assertThat(stuck).failsWithin(Duration.ofSeconds(5)).withThrowableThat()
                .havingRootCause().withMessageContaining("stuck on shutdown, dropping 1 events");
        sink.close();
    }

    @Test
    void noWriteAttemptsAfterShutdownDeadline() {
        AtomicInteger attempts = new AtomicInteger();
        AsyncEventSink sink = AsyncEventSink.start(batch -> {
            attempts.incrementAndGet();
            dead.publish(batch);
        }, 100, Duration.ofMillis(10), Duration.ofMillis(100));
        for (long seq = 1; seq <= 50; seq++) {
            sink.publish(List.of(event(seq)));
        }

        long start = System.nanoTime();
        sink.close();

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        int afterClose = attempts.get();
        sleep(100);
        assertThat(attempts.get()).isEqualTo(afterClose).isLessThan(15);
    }

    @Test
    void closeWaitsUntilPendingEventsAreWritten() {
        AsyncEventSink sink = AsyncEventSink.start(batch -> {
            sleep(200);
            recorder.publish(batch);
        }, 10, BACKOFF, Duration.ofSeconds(5));
        sink.publish(List.of(event(1)));
        sink.close();

        assertThat(recorder.calls).containsExactly(List.of(1L));
    }

    @Test
    void interruptedCloseStillFinishesAndKeepsFlag() {
        AsyncEventSink sink = AsyncEventSink.start(batch -> {
            sleep(100);
            recorder.publish(batch);
        }, 10, BACKOFF, Duration.ofSeconds(5));
        sink.publish(List.of(event(1)));
        Thread.currentThread().interrupt();
        try {
            sink.close();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        assertThat(recorder.calls).containsExactly(List.of(1L));
    }
}
