package io.github.sitkowski01.exchange.engine;

import io.github.sitkowski01.exchange.domain.OrderBook;

import java.util.Objects;

/**
 * Stan, od ktorego silnik spolki zaczyna: arkusz, numer ostatniego zdarzenia i pamiec powtorek.
 * Po restarcie -- odtworzony z dziennika, dzieki czemu numeracja zdarzen i id zlecen
 * ciagna sie dalej, klienci moga anulowac zlecenia zlozone przed restartem, a powtorka
 * zlecenia sprzed restartu nie zlozy go drugi raz.
 *
 * @param sequence numer ostatniego zdarzenia, ktore ten arkusz juz uwzglednia
 */
public record InstrumentState(OrderBook book, long sequence, ClientOrders clientOrders) {

    public InstrumentState {
        Objects.requireNonNull(book, "book");
        Objects.requireNonNull(clientOrders, "clientOrders");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative, was " + sequence);
        }
    }

    public InstrumentState(OrderBook book, long sequence) {
        this(book, sequence, ClientOrders.empty());
    }

    public static InstrumentState empty() {
        return new InstrumentState(new OrderBook(), 0);
    }
}
