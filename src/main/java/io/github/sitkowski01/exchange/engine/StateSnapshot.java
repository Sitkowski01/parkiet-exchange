package io.github.sitkowski01.exchange.engine;

import io.github.sitkowski01.exchange.domain.OrderBook;

import java.util.Objects;

/**
 * Pelny stan spolki do migawki: wszystkie zlecenia czekajace, nie tylko glebokosc.
 *
 * @param sequence numer ostatniego zdarzenia, ktore ten stan juz uwzglednia -- po starcie z migawki
 *                 wystarczy dociagnac zdarzenia od {@code sequence + 1}
 */
public record StateSnapshot(String symbol, long sequence, OrderBook.Snapshot book) {

    public StateSnapshot {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(book, "book");
    }
}
