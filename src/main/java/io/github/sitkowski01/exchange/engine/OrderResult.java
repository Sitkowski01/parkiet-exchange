package io.github.sitkowski01.exchange.engine;

import io.github.sitkowski01.exchange.domain.BookEvent;

import java.util.List;

/**
 * Odpowiedz na zlecenie: nadane id i wszystko, co sie z nim stalo w arkuszu.
 *
 * @param repeated {@code true}, gdy to powtorka ({@link ClientOrderId} juz znany) -- arkusz sie nie zmienil,
 *                 a {@code orderId} i {@code events} opisuja to, co silnik zrobil za pierwszym razem
 */
public record OrderResult(long orderId, List<BookEvent> events, boolean repeated) {

    public OrderResult {
        events = List.copyOf(events);
    }

    public OrderResult(long orderId, List<BookEvent> events) {
        this(orderId, events, false);
    }

    OrderResult asRepeat() {
        return new OrderResult(orderId, events, true);
    }
}
