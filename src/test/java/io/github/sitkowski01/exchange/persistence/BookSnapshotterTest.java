package io.github.sitkowski01.exchange.persistence;

import io.github.sitkowski01.exchange.TestcontainersConfiguration;
import io.github.sitkowski01.exchange.domain.OrderType;
import io.github.sitkowski01.exchange.domain.Side;
import io.github.sitkowski01.exchange.engine.EngineEvent;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Migawka nigdy nie wyprzedza dziennika i nie powtarza sie, gdy spolka sie nie zmienila. */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BookSnapshotterTest {

    private static final List<String> SYMBOLS = List.of("CDR", "PKO");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    private SnapshotStore store;
    private long runId;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate engine_event, book_snapshot, engine_run");
        store = new SnapshotStore(jdbc);
        runId = JdbcEventSink.startRun(jdbc, tx).runId();
    }

    @Test
    void snapshotterSavesOnlyWhatTheJournalAlreadyHasAndOnlyWhenItChanged() {
        JdbcEventSink journalSink = JdbcEventSink.resumeRun(jdbc, tx, runId);
        List<List<EngineEvent>> held = new ArrayList<>();
        boolean[] writing = {false};
        try (MatchingEngine engine = new MatchingEngine(SYMBOLS, 100, Clock.systemUTC(), batch -> {
            if (writing[0]) {
                journalSink.publish(batch);
            } else {
                held.add(batch);
            }
        })) {
            BookSnapshotter snapshotter = new BookSnapshotter(engine, new EventJournal(jdbc, tx), store, runId,
                    Duration.ofSeconds(5));
            engine.instrument("CDR").place(Side.SELL, OrderType.LIMIT, 26300, 10).join();

            snapshotter.snapshotAll();
            assertThat(store.latest(runId, "CDR")).as("dziennik jeszcze nie ma zdarzenia").isEmpty();

            held.forEach(journalSink::publish);
            writing[0] = true;
            snapshotter.snapshotAll();
            assertThat(store.latest(runId, "CDR").orElseThrow().sequence()).isEqualTo(1);
            assertThat(store.latest(runId, "PKO")).as("PKO bez zdarzen").isEmpty();

            jdbc.update("delete from book_snapshot");
            snapshotter.snapshotAll();
            assertThat(store.latest(runId, "CDR")).as("nic sie nie zmienilo od ostatniej migawki").isEmpty();
        }
    }

    @Test
    void intervalMustBePositive() {
        try (MatchingEngine engine = new MatchingEngine(SYMBOLS, 10, Clock.systemUTC(), batch -> {
        })) {
            assertThatThrownBy(() -> BookSnapshotter.start(engine, new EventJournal(jdbc, tx), store, runId,
                    Duration.ZERO, Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
