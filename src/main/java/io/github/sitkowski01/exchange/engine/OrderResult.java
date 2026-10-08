package io.github.sitkowski01.exchange.engine;

import io.github.sitkowski01.exchange.domain.BookEvent;

import java.util.List;

/** Odpowiedz na zlecenie: nadane id i wszystko, co sie z nim stalo w arkuszu. */
public record OrderResult(long orderId, List<BookEvent> events) {

    public OrderResult {
        events = List.copyOf(events);
    }
}
