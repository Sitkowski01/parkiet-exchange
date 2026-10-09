package io.github.sitkowski01.exchange.marketdata;

import io.github.sitkowski01.exchange.engine.BookSnapshot;
import io.github.sitkowski01.exchange.engine.MatchingEngine;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Co {@code interval} wysyla swiezy obraz arkusza spolek, ktore sie zmienily i maja widzow.
 *
 * <p>Obraz bierzemy od silnika (przez jego kolejke), a nie odtwarzamy ze zdarzen: jest spojny
 * co do zlecenia i ma numer ostatniego zdarzenia, ktory pozwala klientowi zszyc go z transakcjami.
 *
 * <p>Wlasny watek, a nie {@code @Scheduled}: przy watkach wirtualnych Spring wykonuje wszystkie
 * zadania okresowe na jednym watku, wiec relay czekajacy na Kafke wstrzymywalby notowania.
 */
public final class BookBroadcaster implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(BookBroadcaster.class.getName());

    private final MarketDataHub hub;
    private final MatchingEngine engine;
    private final int levels;
    private final Duration timeout;
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("book-broadcaster").factory());

    public BookBroadcaster(MarketDataHub hub, MatchingEngine engine, int levels, Duration timeout) {
        this.hub = hub;
        this.engine = engine;
        this.levels = levels;
        this.timeout = timeout;
    }

    public void start(Duration interval) {
        timer.scheduleWithFixedDelay(this::broadcastChangedBooks, interval.toMillis(), interval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        timer.shutdownNow();
    }

    /**
     * Nic stad nie moze wyleciec: wyjatek z zadania {@code scheduleWithFixedDelay} po cichu
     * anuluje wszystkie kolejne uruchomienia i notowania przestalyby plynac na zawsze.
     */
    void broadcastChangedBooks() {
        try {
            Set<String> watched = hub.drainChanged();
            watched.removeIf(symbol -> !hub.hasSubscribers(symbol));
            snapshots(watched).forEach((symbol, snapshot) -> {
                try {
                    hub.broadcast(symbol, await(snapshot));
                } catch (Exception e) {
                    // Silnik zajety (pelna kolejka) albo zamykany: sprobujemy w nastepnej turze.
                    hub.markChanged(symbol);
                    LOG.log(System.Logger.Level.WARNING, "book snapshot for " + symbol + " failed: " + e);
                }
            });
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "book broadcast round failed", e);
        }
    }

    /**
     * Obrazy kilku spolek naraz: najpierw wszystkie zapytania, potem czekanie. Kazda spolka ma
     * wlasny watek silnika, wiec szesc obrazow trwa tyle co jeden, a nie szesc razy dluzej.
     */
    Map<String, CompletableFuture<BookSnapshot>> snapshots(Set<String> symbols) {
        Map<String, CompletableFuture<BookSnapshot>> pending = new LinkedHashMap<>();
        for (String symbol : symbols) {
            pending.put(symbol, engine.instrument(symbol).snapshot(levels));
        }
        return pending;
    }

    /** Czeka na obraz i zwraca gotowa wiadomosc. */
    String await(CompletableFuture<BookSnapshot> snapshot) throws Exception {
        return MarketDataMessages.book(snapshot.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
    }
}
