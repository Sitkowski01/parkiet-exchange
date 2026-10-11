package io.github.sitkowski01.exchange.engine;

import io.github.sitkowski01.exchange.domain.BookEvent;

import java.time.Instant;

/**
 * Zdarzenie arkusza z kontekstem silnika.
 *
 * @param sequence      numer kolejny w obrebie instrumentu, bez dziur -- odbiorca od razu
 *                      widzi, ze cos zgubil, i wie, od ktorego miejsca odtwarzac
 * @param clientOrderId identyfikator klienta zlecenia, ktore wywolalo zdarzenie (takze reszty PKC
 *                      anulowanej z braku ofert); {@code null}, gdy klient go nie podal
 *                      albo zdarzenie to anulowanie na zadanie (DELETE)
 */
public record EngineEvent(String symbol, long sequence, Instant timestamp, BookEvent event, ClientOrderId clientOrderId) {

    public EngineEvent(String symbol, long sequence, Instant timestamp, BookEvent event) {
        this(symbol, sequence, timestamp, event, null);
    }
}
