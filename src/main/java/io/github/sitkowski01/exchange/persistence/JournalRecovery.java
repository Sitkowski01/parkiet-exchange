package io.github.sitkowski01.exchange.persistence;

import io.github.sitkowski01.exchange.config.StartingState;
import io.github.sitkowski01.exchange.domain.OrderBook;
import io.github.sitkowski01.exchange.engine.InstrumentState;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Restart gieldy bez utraty arkusza (event sourcing): stan kazdej spolki to suma zdarzen
 * z dziennika ostatniego przebiegu. Nie ma osobnej tabeli "zlecenia w arkuszu", ktora moglaby
 * rozjechac sie z dziennikiem -- dziennik jest jedynym zrodlem prawdy.
 *
 * <pre>
 * engine_event (run 7, CDR, sequence 1..n) ──► OrderBook.Rebuilder ──► InstrumentState(arkusz, n)
 * </pre>
 *
 * <p>Zapis jest asynchroniczny (group commit), wiec po twardej awarii w dzienniku moze brakowac
 * ostatnich milisekund pracy. Arkusz odtwarza sie wtedy do ostatniego zapisanego zdarzenia --
 * spojny, tylko troche starszy. Po zwyklym zamknieciu {@code AsyncEventSink} zapisuje wszystko.
 */
public final class JournalRecovery {

    private static final System.Logger LOG = System.getLogger(JournalRecovery.class.getName());

    private JournalRecovery() {
    }

    /**
     * @return {@link StartingState#fresh()}, gdy gielda startuje pierwszy raz
     * @throws IllegalStateException gdy dziennik jest niespojny (dziura w numeracji, transakcja
     *                               na nieznanym zleceniu) -- wtedy gielda nie wystartuje
     */
    public static StartingState recover(EventJournal journal, Collection<String> symbols) {
        OptionalLong lastRun = journal.lastRun();
        if (lastRun.isEmpty()) {
            return StartingState.fresh();
        }
        long runId = lastRun.getAsLong();
        Map<String, InstrumentState> instruments = new LinkedHashMap<>();
        for (String symbol : symbols) {
            OrderBook.Rebuilder rebuilder = new OrderBook.Rebuilder();
            long[] sequence = {0};
            journal.replay(runId, symbol, event -> {
                if (event.sequence() != sequence[0] + 1) {
                    throw new IllegalStateException(symbol + ": journal gap, expected sequence "
                            + (sequence[0] + 1) + " but got " + event.sequence());
                }
                sequence[0] = event.sequence();
                rebuilder.apply(event.event());
            });
            if (sequence[0] > 0) {
                instruments.put(symbol, new InstrumentState(rebuilder.finish(), sequence[0]));
            }
        }
        LOG.log(System.Logger.Level.INFO, "resuming run " + runId + ": " + instruments.keySet().stream()
                .map(s -> s + "@" + instruments.get(s).sequence()).toList());
        return StartingState.resume(runId, instruments);
    }
}
