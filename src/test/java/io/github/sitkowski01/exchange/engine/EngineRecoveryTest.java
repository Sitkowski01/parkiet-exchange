package io.github.sitkowski01.exchange.engine;

import io.github.sitkowski01.exchange.domain.BookEvent;
import io.github.sitkowski01.exchange.domain.OrderBook;
import io.github.sitkowski01.exchange.domain.OrderType;
import io.github.sitkowski01.exchange.domain.PriceLevel;
import io.github.sitkowski01.exchange.domain.Side;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Restart gieldy: silnik startuje z arkusza odtworzonego z dziennika i ciagnie numeracje dalej --
 * jakby restartu nie bylo.
 */
class EngineRecoveryTest {

    @Test
    void restartedEngineContinuesWhereTheOldOneStopped() {
        List<EngineEvent> journal = Collections.synchronizedList(new ArrayList<>());
        try (MatchingEngine before = new MatchingEngine(List.of("CDR"), 100, Clock.systemUTC(), journal::addAll)) {
            InstrumentEngine cdr = before.instrument("CDR");
            cdr.place(Side.SELL, OrderType.LIMIT, 10100, 10).join();
            cdr.place(Side.SELL, OrderType.LIMIT, 10200, 5).join();
            cdr.place(Side.BUY, OrderType.LIMIT, 10100, 4).join();
            cdr.cancel(2).join();
        }
        long lastSequence = journal.getLast().sequence();
        OrderBook rebuilt = OrderBook.rebuild(journal.stream().map(EngineEvent::event).toList());

        List<EngineEvent> after = Collections.synchronizedList(new ArrayList<>());
        try (MatchingEngine restarted = new MatchingEngine(List.of("CDR", "PKO"), 100, Clock.systemUTC(),
                after::addAll, Map.of("CDR", new InstrumentState(rebuilt, lastSequence)))) {
            InstrumentEngine cdr = restarted.instrument("CDR");

            BookSnapshot book = cdr.snapshot(10).join();
            assertThat(book.sequence()).isEqualTo(lastSequence);
            assertThat(book.asks()).containsExactly(new PriceLevel(10100, 6, 1));

            // Zlecenie sprzed restartu da sie anulowac; nowe dostaje kolejne id i kolejny numer zdarzenia.
            Optional<BookEvent.OrderCancelled> cancelled = cdr.cancel(1).join();
            assertThat(cancelled).isPresent();
            OrderResult next = cdr.place(Side.BUY, OrderType.LIMIT, 9900, 1).join();
            assertThat(next.orderId()).isEqualTo(4);
            assertThat(after).extracting(EngineEvent::sequence).containsExactly(lastSequence + 1, lastSequence + 2);

            // Spolka bez zapisanego stanu zaczyna od zera.
            assertThat(restarted.instrument("PKO").snapshot(10).join().sequence()).isZero();
        }
    }

    @Test
    void negativeSequenceIsRejected() {
        assertThatThrownBy(() -> new InstrumentState(new OrderBook(), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
