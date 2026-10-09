package io.github.sitkowski01.exchange.api;

import io.github.sitkowski01.exchange.config.ExchangeProperties;
import io.github.sitkowski01.exchange.engine.EngineEvent;
import io.github.sitkowski01.exchange.engine.EventSink;
import io.github.sitkowski01.exchange.engine.MatchingEngine;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Wspolna baza testow HTTP: prawdziwy silnik, bez serwera i bez bazy -- MockMvc wola kontroler
 * bezposrednio. Kazdy test dostaje swiezy silnik (DirtiesContext), wiec id zlecen zaczynaja sie od 1.
 */
@WebMvcTest(OrderController.class)
@Import(ApiTestBase.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
abstract class ApiTestBase {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    GateSink gate;

    @AfterEach
    void releaseEngine() {
        gate.open();
    }

    MvcTestResult place(String symbol, String body) {
        return mvc.post().uri("/api/instruments/{symbol}/orders", symbol)
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    /** Odbiorca zdarzen, ktory potrafi zatrzymac watek silnika, dopoki test go nie wpusci. */
    static final class GateSink implements EventSink {

        private volatile CountDownLatch latch = new CountDownLatch(0);

        void close() {
            latch = new CountDownLatch(1);
        }

        void open() {
            latch.countDown();
        }

        @Override
        public void publish(List<EngineEvent> batch) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @TestConfiguration
    static class Config {

        @Bean
        ExchangeProperties exchangeProperties() {
            return new ExchangeProperties(List.of("CDR", "PKO"), 1, Duration.ofSeconds(2));
        }

        @Bean
        GateSink gateSink() {
            return new GateSink();
        }

        @Bean(destroyMethod = "close")
        MatchingEngine matchingEngine(ExchangeProperties properties, GateSink sink) {
            return new MatchingEngine(properties.symbols(), properties.queueCapacity(), Clock.systemUTC(), sink);
        }
    }
}
