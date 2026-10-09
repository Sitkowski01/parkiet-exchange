package io.github.sitkowski01.exchange.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.github.sitkowski01.exchange.domain.BookEvent;
import io.github.sitkowski01.exchange.domain.BookEvent.OrderCancelled;
import io.github.sitkowski01.exchange.domain.Side;

import java.math.BigDecimal;

/**
 * Zdarzenie arkusza w JSON-ie: ceny w zlotowkach i pole {@code type} mowiace, co sie stalo.
 *
 * <p>Ceny ida jako tekst ({@code "101.25"}): klient w JavaScripcie sparsowalby liczbe
 * do double i zgubil grosze. Tak samo robi np. Binance.
 *
 * <p>Osobne typy zamiast wystawiania {@link BookEvent} wprost: ksztalt JSON-a nie zalezy
 * od rekordow domeny. Enumy domeny ({@code Side}, {@code Reason}) ida po nazwie -- zmiana
 * nazwy stalej zmienia wiec kontrakt API; swiadomy kompromis, zeby nie dublowac enumow.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = EventView.Rested.class, name = "RESTED"),
        @JsonSubTypes.Type(value = EventView.Trade.class, name = "TRADE"),
        @JsonSubTypes.Type(value = EventView.Cancelled.class, name = "CANCELLED")
})
public sealed interface EventView {

    record Rested(long orderId, Side side, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal price,
                  long quantity) implements EventView {
    }

    record Trade(long makerOrderId, long takerOrderId, Side takerSide,
                 @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal price, long quantity) implements EventView {
    }

    record Cancelled(long orderId, long quantity, OrderCancelled.Reason reason) implements EventView {
    }

    static EventView of(BookEvent event) {
        return switch (event) {
            case BookEvent.OrderRested r -> new Rested(r.orderId(), r.side(), Prices.toZloty(r.price()), r.quantity());
            case BookEvent.Trade t -> new Trade(t.makerOrderId(), t.takerOrderId(), t.takerSide(),
                    Prices.toZloty(t.price()), t.quantity());
            case OrderCancelled c -> new Cancelled(c.orderId(), c.quantity(), c.reason());
        };
    }
}
