package io.github.sitkowski01.exchange.persistence;

import io.github.sitkowski01.exchange.TestcontainersConfiguration;
import io.github.sitkowski01.exchange.domain.BookEvent;
import io.github.sitkowski01.exchange.domain.BookEvent.OrderCancelled;
import io.github.sitkowski01.exchange.domain.Side;
import io.github.sitkowski01.exchange.engine.EngineEvent;
import io.github.sitkowski01.exchange.persistence.EventJournal.StoredEvent;
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

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EventJournalTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:15:30.123456Z");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    private EventJournal journal;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate engine_event, engine_run");
        journal = new EventJournal(jdbc);
    }

    /** Zapis i odczyt to dwie strony tego samego formatu: co weszlo, musi wyjsc identyczne. */
    @Test
    void readsBackExactlyWhatWasWritten() {
        JdbcEventSink sink = JdbcEventSink.startRun(jdbc, tx);
        List<EngineEvent> written = List.of(
                new EngineEvent("CDR", 1, NOW, new BookEvent.OrderRested(7, Side.BUY, 10125, 30)),
                new EngineEvent("CDR", 2, NOW, new BookEvent.Trade(7, 8, Side.SELL, 10125, 10)),
                new EngineEvent("CDR", 3, NOW, new OrderCancelled(7, 20, OrderCancelled.Reason.REQUESTED)),
                new EngineEvent("PKO", 1, NOW, new OrderCancelled(1, 5, OrderCancelled.Reason.NO_LIQUIDITY)));
        sink.publish(written);

        List<StoredEvent> read = journal.readAfter(0, 100);

        assertThat(read).extracting(StoredEvent::event).containsExactlyElementsOf(written);
        assertThat(read).extracting(StoredEvent::runId).containsOnly(sink.runId());
    }

    @Test
    void readsInWriteOrderAfterGivenIdWithLimit() {
        JdbcEventSink first = JdbcEventSink.startRun(jdbc, tx);
        JdbcEventSink second = JdbcEventSink.startRun(jdbc, tx);
        second.publish(List.of(rested("PZU", 1)));
        first.publish(List.of(rested("ALE", 1), rested("ALE", 2)));
        second.publish(List.of(rested("CDR", 1)));

        List<StoredEvent> all = journal.readAfter(0, 100);
        assertThat(all).extracting(s -> s.event().symbol()).containsExactly("PZU", "ALE", "ALE", "CDR");
        assertThat(all).extracting(StoredEvent::id).isSorted().doesNotHaveDuplicates();

        List<StoredEvent> page = journal.readAfter(all.get(0).id(), 2);
        assertThat(page).extracting(StoredEvent::id).containsExactly(all.get(1).id(), all.get(2).id());
    }

    private static EngineEvent rested(String symbol, long sequence) {
        return new EngineEvent(symbol, sequence, NOW, new BookEvent.OrderRested(sequence, Side.BUY, 100, 1));
    }
}
