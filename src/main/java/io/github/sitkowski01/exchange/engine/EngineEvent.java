package io.github.sitkowski01.exchange.engine;

import io.github.sitkowski01.exchange.domain.BookEvent;

import java.time.Instant;

/**
 * Zdarzenie arkusza z kontekstem silnika.
 *
 * @param sequence numer kolejny w obrebie instrumentu, bez dziur -- odbiorca od razu
 *                 widzi, ze cos zgubil, i wie, od ktorego miejsca odtwarzac
 */
public record EngineEvent(String symbol, long sequence, Instant timestamp, BookEvent event) {
}
