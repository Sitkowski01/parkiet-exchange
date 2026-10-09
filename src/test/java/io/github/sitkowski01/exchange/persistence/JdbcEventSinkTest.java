package io.github.sitkowski01.exchange.persistence;

import io.github.sitkowski01.exchange.TestcontainersConfiguration;
import io.github.sitkowski01.exchange.domain.BookEvent;
import io.github.sitkowski01.exchange.domain.BookEvent.OrderCancelled;
import io.github.sitkowski01.exchange.domain.Side;
import io.github.sitkowski01.exchange.engine.EngineEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Zapis zdarzen na prawdziwym PostgreSQL. Bez transakcji testowej: sprawdzamy wlasnie to,
 * co odbiorca commituje sam, a cofniecie przez test by to zaslonilo.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JdbcEventSinkTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:15:30.123456Z");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @BeforeEach
    void clean() {
        jdbc.execute("truncate engine_event, engine_run");
    }

    @Test
    void storesEveryEventTypeInOneRow() {
        JdbcEventSink sink = JdbcEventSink.startRun(jdbc, tx);
        sink.publish(List.of(
                event(1, new BookEvent.OrderRested(7, Side.BUY, 10125, 30)),
                event(2, new BookEvent.Trade(7, 8, Side.SELL, 10125, 10)),
                event(3, new OrderCancelled(7, 20, OrderCancelled.Reason.REQUESTED))));

        List<Map<String, Object>> rows = jdbc.queryForList("""
                select run_id, symbol, sequence, occurred_at, type, order_id, maker_order_id, taker_order_id,
                       side, price, quantity, reason
                from engine_event order by sequence""");

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).containsAllEntriesOf(Map.of("run_id", sink.runId(), "symbol", "CDR",
                "sequence", 1L, "type", "RESTED", "order_id", 7L, "side", "BUY", "price", 10125L, "quantity", 30L));
        assertThat(rows.get(0)).containsEntry("maker_order_id", null).containsEntry("reason", null);
        assertThat(rows.get(1)).containsAllEntriesOf(Map.of("type", "TRADE", "maker_order_id", 7L,
                "taker_order_id", 8L, "side", "SELL", "price", 10125L, "quantity", 10L));
        assertThat(rows.get(1)).containsEntry("order_id", null);
        assertThat(rows.get(2)).containsAllEntriesOf(Map.of("type", "CANCELLED", "order_id", 7L,
                "quantity", 20L, "reason", "REQUESTED"));
        assertThat(rows.get(2)).containsEntry("side", null).containsEntry("price", null);
        assertThat(jdbc.queryForObject("select occurred_at from engine_event where sequence = 1", Instant.class))
                .isEqualTo(NOW);
    }

    @Test
    void batchIsAllOrNothing() {
        JdbcEventSink sink = JdbcEventSink.startRun(jdbc, tx);
        // Drugie zdarzenie z tym samym numerem lamie klucz glowny -- pierwsze tez nie moze zostac.
        List<EngineEvent> broken = List.of(
                event(1, new BookEvent.OrderRested(1, Side.BUY, 100, 1)),
                event(1, new BookEvent.OrderRested(2, Side.BUY, 100, 1)));

        assertThatThrownBy(() -> sink.publish(broken)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select count(*) from engine_event", Long.class)).isZero();
    }

    @Test
    void eachRunNumbersEventsFromScratch() {
        JdbcEventSink first = JdbcEventSink.startRun(jdbc, tx);
        JdbcEventSink second = JdbcEventSink.startRun(jdbc, tx);
        first.publish(List.of(event(1, new BookEvent.OrderRested(1, Side.BUY, 100, 1))));
        second.publish(List.of(event(1, new BookEvent.OrderRested(1, Side.SELL, 200, 1))));

        assertThat(second.runId()).isGreaterThan(first.runId());
        assertThat(jdbc.queryForObject("select count(*) from engine_event where sequence = 1", Long.class))
                .isEqualTo(2);
    }

    @Test
    void databaseRejectsRowsOfWrongShape() {
        long run = JdbcEventSink.startRun(jdbc, tx).runId();
        String insert = "insert into engine_event (run_id, symbol, sequence, occurred_at, type, order_id, "
                + "maker_order_id, taker_order_id, side, price, quantity, reason) values (?, 'CDR', ?, now(), ?, ?, ?, ?, ?, ?, ?, ?)";

        // pusta cena (NULL > 0 to NULL, nie false!), transakcja bez makera, anulowanie z cena,
        // nieznany typ, zerowa ilosc, zla strona
        Object[][] invalid = {
                {run, 6, "RESTED", 1, null, null, "BUY", null, 1, null},
                {run, 7, "TRADE", null, 1, 2, "BUY", null, 1, null},
                {run, 1, "TRADE", null, null, 2, "BUY", 100, 1, null},
                {run, 2, "CANCELLED", 1, null, null, null, 100, 1, "REQUESTED"},
                {run, 3, "EXPIRED", 1, null, null, null, null, 1, null},
                {run, 4, "RESTED", 1, null, null, "BUY", 100, 0, null},
                {run, 5, "RESTED", 1, null, null, "HOLD", 100, 1, null}
        };
        for (Object[] row : invalid) {
            assertThatThrownBy(() -> jdbc.update(insert, row)).as(row[2].toString())
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    private static EngineEvent event(long sequence, BookEvent event) {
        return new EngineEvent("CDR", sequence, NOW, event);
    }
}
