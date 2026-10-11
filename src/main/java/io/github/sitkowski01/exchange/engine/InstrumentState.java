package io.github.sitkowski01.exchange.engine;

import io.github.sitkowski01.exchange.domain.OrderBook;

import java.util.Objects;

/**
 * Stan, od ktorego silnik spolki zaczyna: arkusz i numer ostatniego zdarzenia.
 * Po restarcie -- odtworzony z dziennika, dzieki czemu numeracja zdarzen i id zlecen
 * ciagna sie dalej, a klienci moga anulowac zlecenia zlozone przed restartem.
 *
 * @param sequence numer ostatniego zdarzenia, ktore ten arkusz juz uwzglednia
 */
public record InstrumentState(OrderBook book, long sequence) {

    public InstrumentState {
        Objects.requireNonNull(book, "book");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative, was " + sequence);
        }
    }

    public static InstrumentState empty() {
        return new InstrumentState(new OrderBook(), 0);
    }
}
