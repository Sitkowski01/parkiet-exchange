package io.github.sitkowski01.exchange.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

/** Ponowienie zlecenia przez HTTP z tym samym clientOrderId -- np. po 504 z giełdy. */
class ClientOrderIdApiTest extends ApiTestBase {

    private static final String ORDER = """
            {"side":"BUY","type":"LIMIT","price":"101.25","quantity":10,"clientOrderId":"broker-7f3a"}""";

    @Test
    void firstTimeIsCreatedWithTheIdEchoed() {
        assertThat(place("CDR", ORDER))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson().isStrictlyEqualTo("""
                        {"orderId": 1, "runId": 7, "clientOrderId": "broker-7f3a", "events": [
                          {"type": "RESTED", "orderId": 1, "side": "BUY", "price": "101.25", "quantity": 10}
                        ]}""");
    }

    @Test
    void repeatIsOkWithTheOriginalResult() {
        place("CDR", ORDER);

        assertThat(place("CDR", ORDER))
                .hasStatus(HttpStatus.OK)
                .bodyJson().isStrictlyEqualTo("""
                        {"orderId": 1, "runId": 7, "clientOrderId": "broker-7f3a", "repeated": true, "events": [
                          {"type": "RESTED", "orderId": 1, "side": "BUY", "price": "101.25", "quantity": 10}
                        ]}""");
        assertThat(mvc.get().uri("/api/instruments/CDR/book")).bodyJson()
                .extractingPath("$.bids[0].quantity").isEqualTo(10);
    }

    @Test
    void sameIdOnAnotherInstrumentIsAnotherOrder() {
        place("CDR", ORDER);

        MvcTestResult other = place("PKO", ORDER);

        assertThat(other).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void malformedIdIsRejectedBeforeTheEngine() {
        assertThat(place("CDR", """
                {"side":"BUY","type":"LIMIT","price":"1","quantity":1,"clientOrderId":"has space"}"""))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(place("CDR", """
                {"side":"BUY","type":"LIMIT","price":"1","quantity":1,"clientOrderId":""}"""))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(place("CDR", """
                {"side":"BUY","type":"LIMIT","price":"1","quantity":1,"clientOrderId":"%s"}""".formatted("x".repeat(65))))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri("/api/instruments/CDR/book")).bodyJson()
                .extractingPath("$.sequence").isEqualTo(0);
    }

    @Test
    void sameIdForADifferentOrderIsAConflict() {
        place("CDR", ORDER);

        assertThat(place("CDR", """
                {"side":"SELL","type":"LIMIT","price":"101.25","quantity":10,"clientOrderId":"broker-7f3a"}"""))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.detail")
                .isEqualTo("client order id 'broker-7f3a' was already used for a different order");
        assertThat(mvc.get().uri("/api/instruments/CDR/book")).bodyJson()
                .extractingPath("$.asks").asArray().isEmpty();
    }
}
