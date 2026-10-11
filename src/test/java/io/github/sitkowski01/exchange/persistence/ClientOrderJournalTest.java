package io.github.sitkowski01.exchange.persistence;

import io.github.sitkowski01.exchange.TestcontainersConfiguration;
import io.github.sitkowski01.exchange.config.StartingState;
import io.github.sitkowski01.exchange.domain.BookEvent;
import io.github.sitkowski01.exchange.domain.OrderType;
import io.github.sitkowski01.exchange.domain.Side;
import io.github.sitkowski01.exchange.engine.ClientOrderId;
import io.github.sitkowski01.exchange.engine.EngineEvent;
import io.github.sitkowski01.exchange.engine.InstrumentEngine;
import io.github.sitkowski01.exchange.engine.MatchingEngine;
import io.github.sitkowski01.exchange.engine.OrderResult;
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

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Identyfikator klienta w dzienniku: zapis, odczyt i -- najwazniejsze -- powtorka po restarcie gieldy
 * nie sklada zlecenia drugi raz.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ClientOrderJournalTest {

    private static final List<String> SYMBOLS = List.of("CDR", "PKO");
    private static final Instant NOW = Instant.parse("2026-10-11T09:00:00Z");
    private static final ClientOrderId ID = new ClientOrderId("broker-7f3a");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    private EventJournal journal;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate engine_event, book_snapshot, engine_run");
        journal = new EventJournal(jdbc, tx);
    }

    @Test
    void idIsWrittenAndReadBackOnEveryEventOfTheOrder() {
        JdbcEventSink sink = JdbcEventSink.startRun(jdbc, tx);
        sink.publish(List.of(
                new EngineEvent("CDR", 1, NOW, new BookEvent.OrderRested(1, Side.SELL, 100, 5)),
                new EngineEvent("CDR", 2, NOW, new BookEvent.Trade(1, 2, Side.BUY, 100, 5), ID)));

        List<EngineEvent> replayed = new ArrayList<>();
        journal.replay(sink.runId(), "CDR", 0, replayed::add);

        assertThat(replayed).extracting(EngineEvent::clientOrderId).containsExactly(null, ID);
        assertThat(journal.readAfter(0, 10)).extracting(e -> e.event().clientOrderId()).containsExactly(null, ID);
    }

    @Test
    void databaseRefusesAMalformedId() {
        JdbcEventSink.startRun(jdbc, tx);

        assertThatThrownBy(() -> jdbc.update("""
                insert into engine_event (run_id, symbol, sequence, occurred_at, type, order_id, side, price,
                                          quantity, client_order_id)
                values ((select max(id) from engine_run), 'CDR', 1, now(), 'RESTED', 1, 'BUY', 100, 1, 'has space')"""))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void recentClientOrdersTakesWholeOrdersFromTheEndOfTheRun() {
        JdbcEventSink sink = JdbcEventSink.startRun(jdbc, tx);
        ClientOrderId first = new ClientOrderId("a");
        ClientOrderId second = new ClientOrderId("b");
        sink.publish(List.of(
                new EngineEvent("CDR", 1, NOW, new BookEvent.OrderRested(1, Side.SELL, 100, 1), first),
                new EngineEvent("CDR", 2, NOW, new BookEvent.OrderRested(2, Side.SELL, 101, 9)),
                new EngineEvent("CDR", 3, NOW, new BookEvent.Trade(1, 3, Side.BUY, 100, 1), second),
                new EngineEvent("CDR", 4, NOW, new BookEvent.Trade(2, 3, Side.BUY, 101, 2), second),
                new EngineEvent("PKO", 1, NOW, new BookEvent.OrderRested(1, Side.BUY, 50, 1), first)));

        assertThat(journal.recentClientOrders(sink.runId(), "CDR", 1))
                .extracting(EngineEvent::sequence).containsExactly(3L, 4L);
        assertThat(journal.recentClientOrders(sink.runId(), "CDR", 10))
                .extracting(EngineEvent::sequence).containsExactly(1L, 3L, 4L);
        assertThat(journal.recentClientOrders(sink.runId(), "PKO", 10)).singleElement()
                .extracting(EngineEvent::clientOrderId).isEqualTo(first);
        assertThat(journal.recentClientOrders(sink.runId() + 1, "CDR", 10)).isEmpty();
    }

    @Test
    void repeatAfterRestartReturnsTheOriginalOrder() {
        JdbcEventSink first = JdbcEventSink.startRun(jdbc, tx);
        OrderResult original;
        try (MatchingEngine engine = new MatchingEngine(SYMBOLS, 100, Clock.systemUTC(), first)) {
            InstrumentEngine cdr = engine.instrument("CDR");
            cdr.place(Side.SELL, OrderType.LIMIT, 26300, 10).join();
            original = cdr.place(ID, Side.BUY, OrderType.LIMIT, 26300, 4).join();
        }
        first.markClosed();

        // Broker dostal 504, gielda sie zrestartowala, broker ponawia z tym samym identyfikatorem.
        StartingState start = JournalRecovery.recover(journal, new SnapshotStore(jdbc), SYMBOLS);
        JdbcEventSink resumed = JdbcEventSink.resumeRun(jdbc, tx, start.resumedRun().getAsLong());
        try (MatchingEngine engine = new MatchingEngine(SYMBOLS, 100, Clock.systemUTC(), resumed, start.instruments())) {
            OrderResult repeat = engine.instrument("CDR").place(ID, Side.BUY, OrderType.LIMIT, 26300, 4).join();

            assertThat(repeat.repeated()).isTrue();
            assertThat(repeat.orderId()).isEqualTo(original.orderId());
            assertThat(repeat.events()).isEqualTo(original.events());
            assertThat(engine.instrument("CDR").snapshot(5).join().asks().getFirst().quantity()).isEqualTo(6);
        }
        assertThat(jdbc.queryForObject("select count(*) from engine_event", Long.class)).isEqualTo(2);
    }

    @Test
    void reusedIdIsRankedByItsLatestUse() {
        JdbcEventSink sink = JdbcEventSink.startRun(jdbc, tx);
        ClientOrderId reused = new ClientOrderId("a");
        sink.publish(List.of(
                new EngineEvent("CDR", 1, NOW, new BookEvent.OrderRested(1, Side.SELL, 100, 1), reused),
                new EngineEvent("CDR", 2, NOW, new BookEvent.OrderRested(2, Side.SELL, 101, 1), new ClientOrderId("b")),
                new EngineEvent("CDR", 3, NOW, new BookEvent.OrderRested(3, Side.SELL, 102, 1), reused)));

        // Okno jednego zlecenia: najswiezej uzyte jest "a" (zdarzenie 3), wiec "b" wypada.
        assertThat(journal.recentClientOrders(sink.runId(), "CDR", 1))
                .extracting(EngineEvent::clientOrderId).containsOnly(reused);
    }
}
