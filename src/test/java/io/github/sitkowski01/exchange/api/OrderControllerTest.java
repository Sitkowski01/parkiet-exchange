package io.github.sitkowski01.exchange.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/** Sciezki poprawne: zlecenia, transakcje, arkusz i anulowanie przez HTTP. */
class OrderControllerTest extends ApiTestBase {

    @Test
    void listsConfiguredInstruments() {
        assertThat(mvc.get().uri("/api/instruments"))
                .hasStatusOk()
                .bodyJson().isStrictlyEqualTo("[\"CDR\", \"PKO\"]");
    }

    @Test
    void limitOrderRestsInBook() {
        assertThat(place("CDR", "{\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":\"101.25\",\"quantity\":10}"))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson().isStrictlyEqualTo("""
                        {"orderId": 1, "events": [
                          {"type": "RESTED", "orderId": 1, "side": "BUY", "price": "101.25", "quantity": 10}
                        ]}""");
    }

    @Test
    void crossingOrdersTradeAtMakerPrice() {
        place("CDR", "{\"side\":\"SELL\",\"type\":\"LIMIT\",\"price\":100,\"quantity\":5}");
        assertThat(place("CDR", "{\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":105.5,\"quantity\":8}"))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson().isStrictlyEqualTo("""
                        {"orderId": 2, "events": [
                          {"type": "TRADE", "makerOrderId": 1, "takerOrderId": 2, "takerSide": "BUY",
                           "price": "100.00", "quantity": 5},
                          {"type": "RESTED", "orderId": 2, "side": "BUY", "price": "105.50", "quantity": 3}
                        ]}""");
    }

    @Test
    void marketOrderWithoutLiquidityIsCancelled() {
        assertThat(place("PKO", "{\"side\":\"SELL\",\"type\":\"MARKET\",\"quantity\":4}"))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson().isStrictlyEqualTo("""
                        {"orderId": 1, "events": [
                          {"type": "CANCELLED", "orderId": 1, "quantity": 4, "reason": "NO_LIQUIDITY"}
                        ]}""");
    }

    @Test
    void bookShowsAggregatedLevels() {
        place("CDR", "{\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":\"99.90\",\"quantity\":3}");
        place("CDR", "{\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":\"99.90\",\"quantity\":2}");
        place("CDR", "{\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":\"99.80\",\"quantity\":1}");
        place("CDR", "{\"side\":\"SELL\",\"type\":\"LIMIT\",\"price\":\"100.10\",\"quantity\":7}");

        assertThat(mvc.get().uri("/api/instruments/CDR/book?levels=1"))
                .hasStatusOk()
                .bodyJson().isStrictlyEqualTo("""
                        {"symbol": "CDR", "sequence": 4,
                         "bids": [{"price": "99.90", "quantity": 5, "orders": 2}],
                         "asks": [{"price": "100.10", "quantity": 7, "orders": 1}]}""");
    }

    @Test
    void cancelReturnsEventAndSecondCancelIs404() {
        place("CDR", "{\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":50,\"quantity\":10}");

        assertThat(mvc.delete().uri("/api/instruments/CDR/orders/1"))
                .hasStatusOk()
                .bodyJson().isStrictlyEqualTo("""
                        {"type": "CANCELLED", "orderId": 1, "quantity": 10, "reason": "REQUESTED"}""");
        assertThat(mvc.delete().uri("/api/instruments/CDR/orders/1"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.detail").isEqualTo("order 1 is not resting in the CDR book");
    }
}
