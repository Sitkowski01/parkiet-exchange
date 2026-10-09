package io.github.sitkowski01.exchange;

import com.jayway.jsonpath.JsonPath;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import io.github.sitkowski01.exchange.persistence.JdbcEventSink;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Cala aplikacja naraz: HTTP → silnik → PostgreSQL → Kafka (Flyway, Testcontainers).
 * Sprawdza, ze kawalki sa dobrze polaczone, a nie kazda regule z osobna -- to robia testy nizej.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, KafkaContainerConfiguration.class})
class MiniExchangeApplicationTests {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JdbcEventSink sink;

    @Autowired
    ConfigurableApplicationContext context;

    @Autowired
    KafkaConnectionDetails kafka;

    /**
     * Kontekst Springa (a z nim silnik) jest wspoldzielony miedzy testami, wiec nie zakladamy,
     * ze arkusz jest pusty: id bierzemy z odpowiedzi, a numer zdarzenia z obrazu arkusza.
     * Zapis do bazy jest asynchroniczny -- na wiersze czekamy (Awaitility), a nie zakladamy, ze juz sa.
     */
    @Test
    void ordersPlacedOverHttpLandInEventLog() throws Exception {
        long before = JsonPath.<Number>read(mvc.get().uri("/api/instruments/PZU/book").exchange()
                .getResponse().getContentAsString(), "$.sequence").longValue();

        Number sellId = place("PZU", "{\"side\":\"SELL\",\"type\":\"LIMIT\",\"price\":\"250.40\",\"quantity\":10}");
        place("PZU", "{\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":4}");
        assertThat(mvc.delete().uri("/api/instruments/PZU/orders/{id}", sellId)).hasStatusOk();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForList("""
                select concat_ws(' ', sequence - ?, type, side, price, quantity, reason)
                from engine_event where run_id = ? and symbol = 'PZU' and sequence > ? order by sequence""",
                String.class, before, sink.runId(), before)).containsExactly(
                "1 RESTED SELL 25040 10",
                "2 TRADE BUY 25040 4",
                "3 CANCELLED 6 REQUESTED"));
    }

    /**
     * Spring zamyka beany w odwrotnej kolejnosci zaleznosci. Silnik musi sie zamknac przed
     * zapisem, inaczej zdarzenia ostatnich polecen nie mialyby dokad trafic.
     */
    @Test
    void engineShutsDownBeforeItsSinks() {
        assertThat(context.getBeanFactory().getDependenciesForBean("matchingEngine"))
                .contains("eventSink", "marketDataHub")
                .doesNotContain("journal");
    }

    private Number place(String symbol, String body) throws Exception {
        MvcTestResult result = mvc.post().uri("/api/instruments/{symbol}/orders", symbol)
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return JsonPath.read(result.getResponse().getContentAsString(), "$.orderId");
    }

    /**
     * Zdarzenia z dziennika trafiaja do Kafki przez outbox. Czytamy topic zwyklym konsumentem,
     * tak jak zrobi to quote-stream. KGH, bo inne testy w tym kontekscie handluja PZU.
     */
    @Test
    void tradesReachKafkaInOrder() throws Exception {
        place("KGH", "{\"side\":\"SELL\",\"type\":\"LIMIT\",\"price\":\"180.55\",\"quantity\":20}");
        place("KGH", "{\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":\"181.00\",\"quantity\":5}");

        // Dostawa co najmniej raz: jak prawdziwy odbiorca odrzucamy powtorki po numerze zdarzenia.
        Map<Long, String> received = new TreeMap<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", kafka.getBootstrapServers()),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of("exchange.events"));
            // Konsument Kafki nie jest bezpieczny watkowo: poll i close na tym samym watku.
            await().pollInSameThread().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(r -> {
                    if ("KGH".equals(r.key())) {
                        received.putIfAbsent(JsonPath.<Number>read(r.value(), "$.sequence").longValue(), r.value());
                    }
                });
                assertThat(received).hasSize(2);
            });
        }

        List<String> events = new ArrayList<>(received.values());
        assertThat(events.get(0)).contains("\"type\":\"RESTED\"", "\"side\":\"SELL\"", "\"price\":18055");
        assertThat(events.get(1)).contains("\"type\":\"TRADE\"", "\"takerSide\":\"BUY\"", "\"price\":18055",
                "\"quantity\":5");
    }
}
