package io.github.sitkowski01.exchange.persistence;

import io.github.sitkowski01.exchange.engine.AsyncEventSink;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

/**
 * Podpina zapis do bazy jako odbiorce zdarzen silnika ({@code EventSink}). EngineConfig bierze go
 * przez {@code Optional<EventSink>} i nie wie, ze za nim stoi PostgreSQL.
 *
 * <pre>
 * silnik ──► AsyncEventSink (kolejka, watek event-writer) ──► JdbcEventSink ──► engine_event
 * </pre>
 *
 * <p>Flyway migruje baze, zanim powstanie {@link JdbcTemplate} -- Spring Boot ustawia
 * te zaleznosc sam, wiec {@code insert into engine_run} zawsze trafi w gotowa tabele.
 */
@Configuration(proxyBeanMethods = false)
class PersistenceConfig {

    /** Ok. 10 s pracy przy 1000 polecen/s, zanim brak bazy zatrzyma gielde. */
    private static final int QUEUE_CAPACITY = 10_000;
    private static final Duration RETRY_BACKOFF = Duration.ofMillis(100);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    JdbcEventSink journal(JdbcTemplate jdbc, TransactionTemplate tx) {
        return JdbcEventSink.startRun(jdbc, tx);
    }

    /** {@code @Primary}: to ten odbiorca trafia do silnika, a nie {@link JdbcEventSink} bezposrednio. */
    @Bean(destroyMethod = "close")
    @Primary
    AsyncEventSink eventSink(JdbcEventSink journal) {
        return AsyncEventSink.start(journal, QUEUE_CAPACITY, RETRY_BACKOFF, CLOSE_TIMEOUT);
    }

    /**
     * Zegar zamykania rusza, zanim Spring zacznie zamykac beany. Silnik zamyka sie przed
     * odbiorca, a jego watek moze wisiec w {@code publish} na pelnej kolejce -- bez tego
     * zegara martwa baza zablokowalaby wylaczenie aplikacji na zawsze.
     */
    @Bean
    ApplicationListener<ContextClosedEvent> eventWriterShutdownClock(AsyncEventSink eventSink) {
        return event -> eventSink.beginShutdown();
    }
}
