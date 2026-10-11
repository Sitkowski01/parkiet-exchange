package io.github.sitkowski01.exchange.persistence;

import io.github.sitkowski01.exchange.engine.MatchingEngine;
import io.github.sitkowski01.exchange.engine.StateSnapshot;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Co {@code interval} zapisuje migawke kazdej spolki, ktora od ostatniej sie zmienila.
 *
 * <p>Migawka trafia do bazy dopiero, gdy dziennik ma juz wszystkie zdarzenia, ktore ona uwzglednia.
 * Zapis zdarzen jest asynchroniczny: migawka wyprzedzajaca dziennik po awarii zawieralaby zlecenia,
 * ktorych dziennik (a wiec i Kafka) nigdy nie zobaczy. Taka migawka czeka do nastepnej rundy.
 */
public final class BookSnapshotter implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(BookSnapshotter.class.getName());

    private final MatchingEngine engine;
    private final EventJournal journal;
    private final SnapshotStore store;
    private final long runId;
    private final Duration timeout;
    private final Map<String, Long> saved = new HashMap<>();
    private ScheduledExecutorService timer;

    BookSnapshotter(MatchingEngine engine, EventJournal journal, SnapshotStore store, long runId, Duration timeout) {
        this.engine = engine;
        this.journal = journal;
        this.store = store;
        this.runId = runId;
        this.timeout = timeout;
    }

    public static BookSnapshotter start(MatchingEngine engine, EventJournal journal, SnapshotStore store,
                                        long runId, Duration interval, Duration timeout) {
        if (interval.toMillis() <= 0) {
            throw new IllegalArgumentException("snapshot interval must be positive, was " + interval);
        }
        BookSnapshotter snapshotter = new BookSnapshotter(engine, journal, store, runId, timeout);
        snapshotter.timer = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("book-snapshotter").daemon().factory());
        snapshotter.timer.scheduleWithFixedDelay(snapshotter::snapshotAll,
                interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        return snapshotter;
    }

    /** Jedna runda. Blad jednej spolki nie zatrzymuje pozostalych ani kolejnych rund. */
    void snapshotAll() {
        for (String symbol : engine.symbols()) {
            try {
                snapshot(symbol);
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING, symbol + ": snapshot failed: " + e);
            }
        }
    }

    private void snapshot(String symbol) throws Exception {
        StateSnapshot state = engine.instrument(symbol).state().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (state.sequence() == 0 || state.sequence() == saved.getOrDefault(symbol, 0L)) {
            return;
        }
        if (journal.lastSequence(runId, symbol) < state.sequence()) {
            return;
        }
        store.save(runId, state);
        saved.put(symbol, state.sequence());
    }

    /** Konczy rundy, zanim zamknie sie silnik -- runda w trakcie zdazy dokonczyc. */
    @Override
    public void close() throws InterruptedException {
        if (timer != null) {
            timer.shutdown();
            if (!timer.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.log(System.Logger.Level.WARNING, "snapshot round still running at shutdown, interrupting it");
                timer.shutdownNow();
            }
        }
    }
}
