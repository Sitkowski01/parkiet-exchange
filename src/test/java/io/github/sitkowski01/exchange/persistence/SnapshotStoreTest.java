package io.github.sitkowski01.exchange.persistence;

import io.github.sitkowski01.exchange.TestcontainersConfiguration;
import io.github.sitkowski01.exchange.domain.BookEvent.OrderRested;
import io.github.sitkowski01.exchange.domain.OrderBook;
import io.github.sitkowski01.exchange.domain.Side;
import io.github.sitkowski01.exchange.engine.StateSnapshot;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Migawki w prawdziwym PostgreSQL: zapis i odczyt w obie strony, zostaje tylko najnowsza. */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SnapshotStoreTest {

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
    void savedSnapshotComesBackTheSame() {
        StateSnapshot snapshot = new StateSnapshot("CDR", 9, new OrderBook.Snapshot(List.of(
                new OrderRested(3, Side.BUY, 26250, 4), new OrderRested(5, Side.SELL, 26300, 10)), 8));

        store.save(runId, snapshot);
        store.save(runId, snapshot);

        assertThat(store.latest(runId, "CDR")).hasValue(snapshot);
        assertThat(store.latest(runId, "PKO")).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from book_snapshot", Integer.class)).isEqualTo(1);
    }

    @Test
    void onlyTheNewestSnapshotOfEachSymbolInTheCurrentRunIsKept() {
        long oldRun = runId;
        store.save(oldRun, new StateSnapshot("ALE", 3, new OrderBook.Snapshot(List.of(), 3)));
        runId = JdbcEventSink.startRun(jdbc, tx).runId();
        for (long sequence = 1; sequence <= 5; sequence++) {
            store.save(runId, new StateSnapshot("CDR", sequence, new OrderBook.Snapshot(List.of(), sequence)));
        }
        store.save(runId, new StateSnapshot("PKO", 1, new OrderBook.Snapshot(List.of(), 1)));

        assertThat(jdbc.queryForList("select concat(symbol, sequence) from book_snapshot order by 1", String.class))
                .containsExactly("CDR5", "PKO1");
        assertThat(store.latest(runId, "CDR").orElseThrow().sequence()).isEqualTo(5);
    }
}
