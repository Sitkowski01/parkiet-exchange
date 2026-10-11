package io.github.sitkowski01.exchange.persistence;

import io.github.sitkowski01.exchange.config.StartingState;
import io.github.sitkowski01.exchange.domain.OrderBook;
import io.github.sitkowski01.exchange.engine.InstrumentState;
import io.github.sitkowski01.exchange.engine.StateSnapshot;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Restart gieldy bez utraty arkusza (event sourcing): stan kazdej spolki to suma zdarzen
 * z dziennika ostatniego przebiegu. Nie ma osobnej tabeli "zlecenia w arkuszu", ktora moglaby
 * rozjechac sie z dziennikiem -- dziennik jest jedynym zrodlem prawdy.
 *
 * <pre>
 * book_snapshot (run 7, CDR, sequence k) ──┐
 * engine_event  (run 7, CDR, k+1..n)  ─────┴► OrderBook.Rebuilder ──► InstrumentState(arkusz, n)
 * </pre>
 *
 * <p>Migawka (jesli jest) skraca start: zamiast calego przebiegu wystarcza zdarzenia po niej.
 *
 * <p>Zapis jest asynchroniczny (group commit), wiec po twardej awarii w dzienniku moze brakowac
 * ostatnich milisekund pracy. Arkusz odtwarza sie wtedy do ostatniego zapisanego zdarzenia --
 * spojny, tylko troche starszy. Klienci mogli jednak dostac numery zlecen, ktorych dziennik nie
 * zapisal, wiec po awarii (przebieg bez {@code closed_at}) numeracja zlecen przeskakuje.
 */
public final class JournalRecovery {

    /**
     * Przeskok id zlecen po awarii. Wiecej, niz moglo czekac na zapis: kolejka zapisu miesci 10 000
     * paczek (jedno polecenie = jedno id), a jeden zapis bierze najwyzej 256 z nich.
     */
    static final long ORDER_ID_GAP_AFTER_CRASH = 1_000_000;

    private static final System.Logger LOG = System.getLogger(JournalRecovery.class.getName());

    private JournalRecovery() {
    }

    /**
     * @return {@link StartingState#fresh()}, gdy gielda startuje pierwszy raz
     * @throws IllegalStateException gdy dziennik jest niespojny (dziura w numeracji, transakcja
     *                               na nieznanym zleceniu) -- wtedy gielda nie wystartuje
     */
    public static StartingState recover(EventJournal journal, SnapshotStore snapshots, Collection<String> symbols) {
        OptionalLong lastRun = journal.lastRun();
        if (lastRun.isEmpty()) {
            return StartingState.fresh();
        }
        long runId = lastRun.getAsLong();
        long started = System.nanoTime();
        boolean crashed = !journal.closedCleanly(runId);
        Map<String, OrderBook.Rebuilder> rebuilders = new LinkedHashMap<>();
        Map<String, Long> sequences = new LinkedHashMap<>();
        for (String symbol : symbols) {
            Optional<StateSnapshot> snapshot = snapshots.latest(runId, symbol);
            OrderBook.Rebuilder rebuilder = snapshot.map(s -> OrderBook.Rebuilder.from(s.book()))
                    .orElseGet(OrderBook.Rebuilder::new);
            long[] sequence = {snapshot.map(StateSnapshot::sequence).orElse(0L)};
            journal.replay(runId, symbol, sequence[0], event -> {
                if (event.sequence() != sequence[0] + 1) {
                    throw new IllegalStateException(symbol + ": journal gap, expected sequence "
                            + (sequence[0] + 1) + " but got " + event.sequence());
                }
                sequence[0] = event.sequence();
                rebuilder.apply(event.event());
            });
            rebuilders.put(symbol, rebuilder);
            sequences.put(symbol, sequence[0]);
        }
        // Po awarii: prog ponad wszystkie dotychczasowe id, zapisany od razu -- przezyje tez kolejny,
        // juz czysty restart, w ktorym dziennik znowu pokazalby stare numery jako ostatnie.
        long floor = journal.orderIdFloor(runId);
        if (crashed) {
            long last = rebuilders.values().stream().mapToLong(OrderBook.Rebuilder::lastOrderId).max().orElse(0);
            floor = Math.max(floor, last + ORDER_ID_GAP_AFTER_CRASH);
            journal.raiseOrderIdFloor(runId, floor);
        }
        Map<String, InstrumentState> instruments = new LinkedHashMap<>();
        for (String symbol : symbols) {
            OrderBook.Rebuilder rebuilder = rebuilders.get(symbol);
            rebuilder.reserveOrderIdsUpTo(floor);
            if (sequences.get(symbol) > 0 || floor > 0) {
                instruments.put(symbol, new InstrumentState(rebuilder.finish(), sequences.get(symbol)));
            }
        }
        LOG.log(System.Logger.Level.INFO, "resuming run " + runId + (crashed ? " after a crash" : "") + " ("
                + (System.nanoTime() - started) / 1_000_000 + " ms): "
                + instruments.keySet().stream().map(s -> s + "@" + instruments.get(s).sequence()).toList());
        return StartingState.resume(runId, instruments);
    }
}
