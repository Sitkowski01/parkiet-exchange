package io.github.sitkowski01.exchange.persistence;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Podpina zapis do bazy jako odbiorce zdarzen silnika ({@code EventSink}). EngineConfig bierze go
 * przez {@code ObjectProvider<EventSink>} i nie wie, ze za nim stoi PostgreSQL.
 *
 * <p>Flyway migruje baze, zanim powstanie {@link JdbcTemplate} -- Spring Boot ustawia
 * te zaleznosc sam, wiec {@code insert into engine_run} zawsze trafi w gotowa tabele.
 */
@Configuration(proxyBeanMethods = false)
class PersistenceConfig {

    @Bean
    JdbcEventSink eventSink(JdbcTemplate jdbc, TransactionTemplate tx) {
        return JdbcEventSink.startRun(jdbc, tx);
    }
}
