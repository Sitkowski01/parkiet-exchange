package io.github.sitkowski01.exchange.persistence;

import io.github.sitkowski01.exchange.TestcontainersConfiguration;
import io.github.sitkowski01.exchange.config.StartingState;
import io.github.sitkowski01.exchange.domain.BookEvent;
import io.github.sitkowski01.exchange.domain.OrderType;
import io.github.sitkowski01.exchange.domain.PriceLevel;
import io.github.sitkowski01.exchange.domain.Side;
import io.github.sitkowski01.exchange.engine.BookSnapshot;
import io.github.sitkowski01.exchange.engine.EngineEvent;
import io.github.sitkowski01.exchange.engine.InstrumentEngine;
import io.github.sitkowski01.exchange.engine.MatchingEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Restart gieldy na prawdziwej bazie: silnik pisze do dziennika, zamyka sie, a nowy silnik
 * startuje z arkusza odtworzonego z tego dziennika i pisze dalej do tego samego przebiegu.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JournalRecoveryTest {

    private static final List<String> SYMBOLS = List.of("CDR", "PKO");
    private static final Instant NOW = Instant.parse("2026-10-11T09:00:00Z");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    private EventJournal journal;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate engine_event, engine_run");
        journal = new EventJournal(jdbc, tx);
    }

    @Test
    void firstStartIsFresh() {
        assertThat(JournalRecovery.recover(journal, SYMBOLS)).isEqualTo(StartingState.fresh());
    }

    @Test
    void restartContinuesTheSameRunWithTheSameBook() {
        JdbcEventSink first = JdbcEventSink.startRun(jdbc, tx);
        try (MatchingEngine engine = new MatchingEngine(SYMBOLS, 100, Clock.systemUTC(), first)) {
            InstrumentEngine cdr = engine.instrument("CDR");
            cdr.place(Side.SELL, OrderType.LIMIT, 26300, 10).join();
            cdr.place(Side.SELL, OrderType.LIMIT, 26350, 5).join();
            cdr.place(Side.BUY, OrderType.MARKET, 0, 4).join();
        }

        StartingState start = JournalRecovery.recover(journal, SYMBOLS);
        assertThat(start.resumedRun()).hasValue(first.runId());
        assertThat(start.instruments()).containsOnlyKeys("CDR");

        JdbcEventSink resumed = JdbcEventSink.resumeRun(jdbc, tx, start.resumedRun().getAsLong());
        try (MatchingEngine engine = new MatchingEngine(SYMBOLS, 100, Clock.systemUTC(), resumed,
                start.instruments())) {
            InstrumentEngine cdr = engine.instrument("CDR");
            BookSnapshot book = cdr.snapshot(5).join();
            assertThat(book.sequence()).isEqualTo(3);
            assertThat(book.asks()).containsExactly(new PriceLevel(26300, 6, 1), new PriceLevel(26350, 5, 1));

            assertThat(cdr.place(Side.BUY, OrderType.LIMIT, 26000, 1).join().orderId()).isEqualTo(4);
            assertThat(cdr.cancel(2).join()).isPresent();
        }

        assertThat(jdbc.queryForList("select concat_ws(' ', run_id, sequence, type) from engine_event "
                + "where symbol = 'CDR' order by sequence", String.class)).containsExactly(
                first.runId() + " 1 RESTED", first.runId() + " 2 RESTED", first.runId() + " 3 TRADE",
                first.runId() + " 4 RESTED", first.runId() + " 5 CANCELLED");
    }

    @Test
    void onlyTheLastRunIsReplayed() {
        JdbcEventSink.startRun(jdbc, tx).publish(List.of(rested("CDR", 1, 1)));
        JdbcEventSink last = JdbcEventSink.startRun(jdbc, tx);
        last.publish(List.of(rested("PKO", 1, 1), rested("PKO", 2, 2)));

        StartingState start = JournalRecovery.recover(journal, SYMBOLS);

        assertThat(start.resumedRun()).hasValue(last.runId());
        assertThat(start.instruments()).containsOnlyKeys("PKO");
        assertThat(start.instruments().get("PKO").sequence()).isEqualTo(2);
        assertThat(start.instruments().get("PKO").book().lastOrderId()).isEqualTo(2);
    }

    @Test
    void gapInJournalStopsTheStart() {
        JdbcEventSink.startRun(jdbc, tx).publish(List.of(rested("CDR", 1, 1), rested("CDR", 3, 2)));

        assertThatThrownBy(() -> JournalRecovery.recover(journal, SYMBOLS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CDR: journal gap, expected sequence 2 but got 3");
    }

    @Test
    void inconsistentJournalStopsTheStart() {
        JdbcEventSink.startRun(jdbc, tx).publish(List.of(
                new EngineEvent("CDR", 1, NOW, new BookEvent.Trade(9, 10, Side.BUY, 100, 1))));

        assertThatThrownBy(() -> JournalRecovery.recover(journal, SYMBOLS))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("does not match");
    }

    private static EngineEvent rested(String symbol, long sequence, long orderId) {
        return new EngineEvent(symbol, sequence, NOW, new BookEvent.OrderRested(orderId, Side.BUY, 100, 1));
    }
}
