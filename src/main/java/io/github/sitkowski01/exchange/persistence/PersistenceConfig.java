package io.github.sitkowski01.exchange.persistence;

import io.github.sitkowski01.exchange.config.EngineSink;
import io.github.sitkowski01.exchange.config.ExchangeProperties;
import io.github.sitkowski01.exchange.config.ExchangeRun;
import io.github.sitkowski01.exchange.config.StartingState;
import io.github.sitkowski01.exchange.engine.AsyncEventSink;
import io.github.sitkowski01.exchange.engine.MatchingEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;

/**
 * Podpina zapis do bazy jako odbiorce zdarzen silnika ({@code EventSink}). EngineConfig bierze go
 * (razem z innymi {@link EngineSink}) i nie wie, ze za nim stoi PostgreSQL.
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
    EventJournal eventJournal(JdbcTemplate jdbc, TransactionTemplate tx) {
        return new EventJournal(jdbc, tx);
    }

    /** Pierwsza rzecz przed odczytem dziennika: druga gielda na tej samej bazie nie wystartuje. */
    @Bean(destroyMethod = "close")
    ExchangeLock exchangeLock(DataSource dataSource) throws SQLException {
        return ExchangeLock.acquire(dataSource);
    }

    @Bean
    SnapshotStore snapshotStore(JdbcTemplate jdbc) {
        return new SnapshotStore(jdbc);
    }

    /**
     * Po restarcie gielda ciagnie ostatni przebieg dalej, z arkuszami odtworzonymi z dziennika.
     * {@code exchange.journal.resume=false} -- zawsze nowy przebieg z pustymi arkuszami.
     */
    @Bean
    StartingState startingState(ExchangeLock lock, EventJournal journal, SnapshotStore snapshots,
                                ExchangeProperties exchange,
                                @Value("${exchange.journal.resume:true}") boolean resume) {
        return resume ? JournalRecovery.recover(journal, snapshots, exchange.symbols()) : StartingState.fresh();
    }

    @Bean
    JdbcEventSink journal(JdbcTemplate jdbc, TransactionTemplate tx, StartingState start) {
        return start.resumedRun().isPresent()
                ? JdbcEventSink.resumeRun(jdbc, tx, start.resumedRun().getAsLong())
                : JdbcEventSink.startRun(jdbc, tx);
    }

    /** Przebieg, ktory widzi API: ten sam, pod ktorym dziennik zapisuje zdarzenia. */
    @Bean
    ExchangeRun exchangeRun(JdbcEventSink journal) {
        return new ExchangeRun(journal.runId());
    }

    /** {@link EngineSink}: do silnika trafia ten odbiorca, a nie {@link JdbcEventSink} bezposrednio. */
    @Bean(destroyMethod = "close")
    @EngineSink
    AsyncEventSink eventSink(JdbcEventSink journal) {
        return AsyncEventSink.start(journal, QUEUE_CAPACITY, RETRY_BACKOFF, CLOSE_TIMEOUT, journal::markClosed);
    }

    /**
     * Migawki arkuszy co {@code exchange.journal.snapshot-interval}: start czyta najnowsza i dociaga tylko
     * zdarzenia po niej. Zalezy od silnika, wiec Spring zamyka go przed silnikiem.
     */
    @Bean(destroyMethod = "close")
    BookSnapshotter bookSnapshotter(MatchingEngine engine, EventJournal journal, SnapshotStore snapshots,
                                    JdbcEventSink sink, ExchangeProperties exchange,
                                    @Value("${exchange.journal.snapshot-interval:1m}") Duration interval) {
        return BookSnapshotter.start(engine, journal, snapshots, sink.runId(), interval, exchange.requestTimeout());
    }
}
