package io.github.sitkowski01.exchange.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/** Bledy: 400 za tresc, 404 za zasob, 503/504 gdy silnik nie nadaza. */
class OrderControllerErrorsTest extends ApiTestBase {

    @Test
    void unknownInstrumentIs404() {
        assertThat(place("XYZ", "{\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":1}"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.detail").isEqualTo("unknown instrument: XYZ");
        assertThat(place("XYZ", "{\"side\":\"BUY\",\"type\":\"MARKET\",\"price\":10,\"quantity\":1}"))
                .as("nieznany zasob przed bledna trescia").hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri("/api/instruments/XYZ/book")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.delete().uri("/api/instruments/XYZ/orders/1")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void invalidOrdersAre400AndDoNotConsumeIds() {
        String[] invalid = {
                "{\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":1}",
                "{\"side\":\"BUY\",\"type\":\"MARKET\",\"price\":10,\"quantity\":1}",
                "{\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":\"10.001\",\"quantity\":1}",
                "{\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":-1,\"quantity\":1}",
                "{\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":10,\"quantity\":0}",
                "{\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":10,\"quantity\":1000000001}",
                "{\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":10}",
                "{\"type\":\"LIMIT\",\"price\":10,\"quantity\":1}",
                "{\"side\":\"HOLD\",\"type\":\"LIMIT\",\"price\":10,\"quantity\":1}",
                "{\"side\":\"BUY\",\"type\":\"STOP\",\"price\":10,\"quantity\":1}",
                "{\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":10,\"quantity\":10.9}",
                "{\"side\":1,\"type\":\"LIMIT\",\"price\":10,\"quantity\":1}",
                "{\"side\":\"BUY\",\"type\":0,\"price\":10,\"quantity\":1}",
                "not json"
        };
        for (String body : invalid) {
            assertThat(place("CDR", body)).as(body).hasStatus(HttpStatus.BAD_REQUEST);
        }
        assertThat(place("CDR", "{\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":10,\"quantity\":1}"))
                .bodyJson().extractingPath("$.orderId").isEqualTo(1);
    }

    @Test
    void bookLevelsOutsideRangeAre400() {
        assertThat(mvc.get().uri("/api/instruments/CDR/book?levels=0")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri("/api/instruments/CDR/book?levels=101")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri("/api/instruments/CDR/book?levels=100")).hasStatusOk();
    }

    /**
     * Silnik zatrzymany w odbiorcy zdarzen: pierwsze zlecenie czeka na niego (504),
     * drugie zajmuje jedyne miejsce w kolejce (504), trzecie nie ma gdzie wejsc (503).
     */
    @Test
    void stuckEngineGives504ThenFullQueueGives503() {
        gate.close();
        String order = "{\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":10,\"quantity\":1}";

        assertThat(place("CDR", order)).hasStatus(HttpStatus.GATEWAY_TIMEOUT)
                .bodyJson().extractingPath("$.detail").asString().contains("may still be executed");
        assertThat(place("CDR", order)).hasStatus(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(place("CDR", order)).hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .bodyJson().extractingPath("$.detail").isEqualTo("CDR: queue full");

        // Inne instrumenty dzialaja dalej -- kazdy ma wlasny watek.
        assertThat(mvc.get().uri("/api/instruments/PKO/book")).hasStatusOk();
    }
}
