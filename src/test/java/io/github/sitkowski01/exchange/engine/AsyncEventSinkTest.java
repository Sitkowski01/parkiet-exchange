package io.github.sitkowski01.exchange.engine;

import io.github.sitkowski01.exchange.engine.SinkFixtures.Recorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static io.github.sitkowski01.exchange.engine.SinkFixtures.await;
import static io.github.sitkowski01.exchange.engine.SinkFixtures.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Normalna praca: kolejnosc, sklejanie paczek, cykl zycia. Awarie -- AsyncEventSinkFailureTest. */
@Timeout(10)
class AsyncEventSinkTest {

    private static final Duration BACKOFF = Duration.ofMillis(1);

    private final Recorder recorder = new Recorder();

    @Test
    void deliversEverythingInOrder() {
        AsyncEventSink sink = AsyncEventSink.start(recorder, 100, BACKOFF, Duration.ofSeconds(1));
        for (long seq = 1; seq <= 1000; seq++) {
            sink.publish(List.of(event(seq)));
        }
        sink.close();

        assertThat(recorder.calls.stream().flatMap(List::stream).toList())
                .containsExactlyElementsOf(LongStream.rangeClosed(1, 1000).boxed().toList());
    }

    @Test
    void batchesWaitingTogetherAreWrittenInOneCall() throws InterruptedException {
        CountDownLatch writerBusy = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        EventSink slow = batch -> {
            writerBusy.countDown();
            await(release);
            recorder.publish(batch);
        };
        AsyncEventSink sink = AsyncEventSink.start(slow, 100, BACKOFF, Duration.ofSeconds(1));

        sink.publish(List.of(event(1)));
        assertThat(writerBusy.await(5, TimeUnit.SECONDS)).isTrue();
        sink.publish(List.of(event(2), event(3)));
        sink.publish(List.of(event(4)));
        release.countDown();
        sink.close();

        assertThat(recorder.calls).containsExactly(List.of(1L), List.of(2L, 3L, 4L));
    }

    @Test
    void oneWriteTakesAtMost256Batches() throws InterruptedException {
        CountDownLatch writerBusy = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        EventSink slow = batch -> {
            writerBusy.countDown();
            await(release);
            recorder.publish(batch);
        };
        AsyncEventSink sink = AsyncEventSink.start(slow, 1000, BACKOFF, Duration.ofSeconds(5));
        sink.publish(List.of(event(0)));
        assertThat(writerBusy.await(5, TimeUnit.SECONDS)).isTrue();
        for (long seq = 1; seq <= 300; seq++) {
            sink.publish(List.of(event(seq)));
        }
        release.countDown();
        sink.close();

        assertThat(recorder.calls).extracting(List::size).containsExactly(1, 256, 44);
    }

    @Test
    void publishAfterCloseIsRejected() {
        AsyncEventSink sink = AsyncEventSink.start(recorder, 10, BACKOFF, Duration.ofSeconds(1));
        sink.close();
        sink.close();

        assertThatThrownBy(() -> sink.publish(List.of(event(1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed, dropping 1 events");
    }

    @Test
    void interruptedPublisherStillDeliversAndKeepsFlag() {
        AsyncEventSink sink = AsyncEventSink.start(recorder, 10, BACKOFF, Duration.ofSeconds(1));
        Thread.currentThread().interrupt();
        try {
            sink.publish(List.of(event(1)));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        sink.close();

        assertThat(recorder.calls).containsExactly(List.of(1L));
    }

    @Test
    void zeroBackoffIsRejected() {
        assertThatThrownBy(() -> AsyncEventSink.start(recorder, 10, Duration.ZERO, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
