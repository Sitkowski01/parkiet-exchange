package io.github.sitkowski01.exchange.engine;

import io.github.sitkowski01.exchange.domain.BookEvent.OrderCancelled;
import io.github.sitkowski01.exchange.domain.BookEvent.OrderRested;
import io.github.sitkowski01.exchange.domain.BookEvent.Trade;
import io.github.sitkowski01.exchange.domain.PriceLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.github.sitkowski01.exchange.domain.BookEvent.OrderCancelled.Reason.REQUESTED;
import static io.github.sitkowski01.exchange.domain.OrderType.LIMIT;
import static io.github.sitkowski01.exchange.domain.OrderType.MARKET;
import static io.github.sitkowski01.exchange.domain.Side.BUY;
import static io.github.sitkowski01.exchange.domain.Side.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstrumentEngineTest {

    private static final Instant NOW = Instant.parse("2026-10-09T09:00:00Z");

    private final List<EngineEvent> published = new CopyOnWriteArrayList<>();
    private final InstrumentEngine engine =
            InstrumentEngine.start("CDR", 16, Clock.fixed(NOW, ZoneOffset.UTC), published::addAll);

    @AfterEach
    void close() {
        engine.close();
    }

    @Test
    void assignsIncreasingIdsAndMatches() {
        OrderResult first = engine.place(SELL, LIMIT, 100, 10).join();
        OrderResult second = engine.place(BUY, LIMIT, 100, 4).join();

        assertThat(engine.symbol()).isEqualTo("CDR");
        assertThat(first).isEqualTo(new OrderResult(1, List.of(new OrderRested(1, SELL, 100, 10))));
        assertThat(second).isEqualTo(new OrderResult(2, List.of(new Trade(1, 2, BUY, 100, 4))));
    }

    @Test
    void publishesEveryEventWithGaplessSequenceAndTime() {
        engine.place(SELL, LIMIT, 100, 10).join();
        engine.place(BUY, MARKET, 0, 4).join();
        assertThat(engine.cancel(1).join()).contains(new OrderCancelled(1, 6, REQUESTED));

        assertThat(published).containsExactly(
                new EngineEvent("CDR", 1, NOW, new OrderRested(1, SELL, 100, 10)),
                new EngineEvent("CDR", 2, NOW, new Trade(1, 2, BUY, 100, 4)),
                new EngineEvent("CDR", 3, NOW, new OrderCancelled(1, 6, REQUESTED)));
    }

    @Test
    void cancelOfUnknownOrderPublishesNothing() {
        assertThat(engine.cancel(42).join()).isEmpty();
        assertThat(published).isEmpty();
    }

    @Test
    void snapshotShowsBookAndLastSequence() {
        engine.place(BUY, LIMIT, 99, 5).join();
        engine.place(SELL, LIMIT, 101, 7).join();

        assertThat(engine.snapshot(10).join()).isEqualTo(new BookSnapshot(
                "CDR", 2, List.of(new PriceLevel(99, 5, 1)), List.of(new PriceLevel(101, 7, 1))));
    }

    @Test
    void invalidOrderFailsWithoutConsumingId() {
        assertThatThrownBy(() -> engine.place(BUY, LIMIT, 100, 0).join())
                .hasCauseInstanceOf(IllegalArgumentException.class);

        assertThat(engine.place(BUY, LIMIT, 100, 1).join().orderId()).isEqualTo(1);
    }

    @Test
    void runsCommandsOnItsOwnThread() {
        List<String> threads = new CopyOnWriteArrayList<>();
        try (InstrumentEngine pko = InstrumentEngine.start("PKO", 4, Clock.systemUTC(),
                batch -> threads.add(Thread.currentThread().getName()))) {
            pko.place(BUY, LIMIT, 100, 1).join();
        }
        assertThat(threads).containsExactly("engine-PKO");
    }

    @Test
    void failingSinkDoesNotStopEngine() {
        try (InstrumentEngine pko = InstrumentEngine.start("PKO", 4, Clock.systemUTC(), batch -> {
            throw new IllegalStateException("broker down");
        })) {
            assertThat(pko.place(BUY, LIMIT, 100, 1).join().orderId()).isEqualTo(1);
            assertThat(pko.place(BUY, LIMIT, 100, 1).join().orderId()).isEqualTo(2);
        }
    }
}
