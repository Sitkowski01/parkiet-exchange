package io.github.sitkowski01.exchange.api;

import io.github.sitkowski01.exchange.domain.OrderRequest;
import io.github.sitkowski01.exchange.domain.OrderType;
import io.github.sitkowski01.exchange.domain.Side;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Cialo {@code POST /api/instruments/{symbol}/orders}.
 *
 * @param price cena w zlotowkach; wymagana dla LIMIT, zabroniona dla MARKET
 */
public record PlaceOrderRequest(
        @NotNull Side side,
        @NotNull OrderType type,
        @Positive BigDecimal price,
        @NotNull @Positive @Max(OrderRequest.MAX_QUANTITY) Long quantity) {

    /**
     * Cena w groszach, sprawdzona jeszcze przed kolejka silnika -- zle zlecenie
     * nie zajmuje miejsca, na ktore czekaja poprawne.
     */
    long priceInGrosze() {
        return switch (type) {
            case LIMIT -> {
                if (price == null) {
                    throw new InvalidOrderException("limit order requires a price");
                }
                yield Prices.toGrosze(price);
            }
            case MARKET -> {
                if (price != null) {
                    throw new InvalidOrderException("market order must not have a price");
                }
                yield 0;
            }
        };
    }
}
