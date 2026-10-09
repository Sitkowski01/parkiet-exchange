package io.github.sitkowski01.exchange;

import com.jayway.jsonpath.JsonPath;
import io.github.sitkowski01.exchange.persistence.JdbcEventSink;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cala aplikacja naraz: HTTP → silnik → PostgreSQL (Flyway, Testcontainers).
 * Sprawdza, ze kawalki sa dobrze polaczone, a nie kazda regule z osobna -- to robia testy nizej.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MiniExchangeApplicationTests {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JdbcEventSink sink;

    /**
     * Kontekst Springa (a z nim silnik) jest wspoldzielony miedzy testami, wiec nie zakladamy,
     * ze arkusz jest pusty: id bierzemy z odpowiedzi, a dziennik czytamy od biezacego miejsca.
     */
    @Test
    void ordersPlacedOverHttpLandInEventLog() throws Exception {
        long before = jdbc.queryForObject(
                "select coalesce(max(sequence), 0) from engine_event where run_id = ? and symbol = 'PZU'",
                Long.class, sink.runId());

        Number sellId = place("{\"side\":\"SELL\",\"type\":\"LIMIT\",\"price\":\"250.40\",\"quantity\":10}");
        place("{\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":4}");
        assertThat(mvc.delete().uri("/api/instruments/PZU/orders/{id}", sellId)).hasStatusOk();

        List<String> log = jdbc.queryForList("""
                select concat_ws(' ', sequence - ?, type, side, price, quantity, reason)
                from engine_event where run_id = ? and symbol = 'PZU' and sequence > ? order by sequence""",
                String.class, before, sink.runId(), before);

        assertThat(log).containsExactly(
                "1 RESTED SELL 25040 10",
                "2 TRADE BUY 25040 4",
                "3 CANCELLED 6 REQUESTED");
    }

    private Number place(String body) throws Exception {
        MvcTestResult result = mvc.post().uri("/api/instruments/PZU/orders")
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return JsonPath.read(result.getResponse().getContentAsString(), "$.orderId");
    }
}
