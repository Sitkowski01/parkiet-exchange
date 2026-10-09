package io.github.sitkowski01.exchange.engine;

import io.github.sitkowski01.exchange.domain.BookEvent;
import io.github.sitkowski01.exchange.domain.Side;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/** Wspolne kawalki testow AsyncEventSink. */
final class SinkFixtures {

    private SinkFixtures() {
    }

    /** Odbiorca, ktory pamieta kazde wywolanie osobno -- widac, co zostalo sklejone w jeden zapis. */
    static final class Recorder implements EventSink {

        final List<List<Long>> calls = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void publish(List<EngineEvent> batch) {
            calls.add(batch.stream().map(EngineEvent::sequence).toList());
        }
    }

    static EngineEvent event(long sequence) {
        return new EngineEvent("CDR", sequence, Instant.EPOCH, new BookEvent.OrderRested(sequence, Side.BUY, 100, 1));
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
