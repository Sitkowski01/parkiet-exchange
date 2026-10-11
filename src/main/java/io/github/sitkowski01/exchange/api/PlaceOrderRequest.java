package io.github.sitkowski01.exchange.api;

import io.github.sitkowski01.exchange.domain.OrderRequest;
import io.github.sitkowski01.exchange.domain.OrderType;
import io.github.sitkowski01.exchange.domain.Side;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
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
        @Schema(description = "LIMIT czeka w arkuszu; MARKET (PKC) bierze, co jest, resztę anuluje")
        @NotNull OrderType type,
        @Schema(description = "Złotówki, najwyżej 2 miejsca po przecinku; "
                + "wymagana dla LIMIT, zabroniona dla MARKET")
        @Positive @DecimalMax("10000000") BigDecimal price,
        @Schema(example = "10")
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
