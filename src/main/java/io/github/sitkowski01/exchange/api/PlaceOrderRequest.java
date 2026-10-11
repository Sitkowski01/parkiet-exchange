package io.github.sitkowski01.exchange.api;

import io.github.sitkowski01.exchange.domain.OrderRequest;
import io.github.sitkowski01.exchange.domain.OrderType;
import io.github.sitkowski01.exchange.domain.Side;
import io.github.sitkowski01.exchange.engine.ClientOrderId;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Cialo {@code POST /api/instruments/{symbol}/orders}.
 *
 * @param price         cena w zlotowkach; wymagana dla LIMIT, zabroniona dla MARKET
 * @param clientOrderId opcjonalny identyfikator klienta (ClOrdID z FIX); ponowienie z tym samym
 *                      zwraca pierwotny wynik zamiast skladac zlecenie drugi raz
 */
public record PlaceOrderRequest(
        @NotNull Side side,
        @Schema(description = "LIMIT czeka w arkuszu; MARKET (PKC) bierze, co jest, resztę anuluje")
        @NotNull OrderType type,
        @Schema(description = "Złotówki, najwyżej 2 miejsca po przecinku; "
                + "wymagana dla LIMIT, zabroniona dla MARKET")
        @Positive @DecimalMax("10000000") BigDecimal price,
        @Schema(example = "10")
        @NotNull @Positive @Max(OrderRequest.MAX_QUANTITY) Long quantity,
        @Schema(description = "Opcjonalny identyfikator klienta (1-64 znaki A-Z a-z 0-9 . _ : -); "
                + "ponowienie z tym samym zwraca pierwotny wynik", example = "broker-7f3a")
        @Pattern(regexp = ClientOrderId.FORMAT) String clientOrderId) {

    /** Zlecenie bez identyfikatora klienta -- jak przed jego wprowadzeniem. */
    public PlaceOrderRequest(Side side, OrderType type, BigDecimal price, Long quantity) {
        this(side, type, price, quantity, null);
    }

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
