package io.github.sitkowski01.exchange.marketdata;

import io.github.sitkowski01.exchange.domain.BookEvent;
import io.github.sitkowski01.exchange.engine.EngineEvent;
import io.github.sitkowski01.exchange.engine.EventSink;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rozdziela zdarzenia silnika miedzy klientow WebSocket.
 *
 * <p>Transakcje ida do subskrybentow spolki od razu. Arkusz -- nie: hub tylko zapamietuje, ktore
 * spolki sie zmienily, a {@link BookBroadcaster} co 100 ms wysyla ich swiezy obraz (conflation).
 * Przy 1000 zmian na sekunde klient dostaje 10 obrazow, a nie 1000 -- i zawsze najnowszy.
 *
 * <p><b>Notowania sa stratne, dziennik nie.</b> {@link #publish} wola watek silnika i nigdy nie czeka:
 * gdy kolejka huba jest pelna, paczka przepada, a jej spolki sa oznaczane jako zmienione. Klient
 * zobaczy dziure w {@code sequence} transakcji, ale za chwile dostanie swiezy obraz arkusza.
 * Handel nie moze stanac dlatego, ze ktos oglada notowania.
 */
public final class MarketDataHub implements EventSink, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(MarketDataHub.class.getName());

    private final Map<String, Set<ClientConnection>> subscribers = new ConcurrentHashMap<>();
    private final Set<String> changed = ConcurrentHashMap.newKeySet();
    private final BlockingQueue<List<EngineEvent>> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final Thread thread;
    private volatile boolean running = true;

    public MarketDataHub(int capacity) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.thread = Thread.ofPlatform().name("market-data").start(this::run);
    }

    /** Watek silnika: wrzuca paczke i wraca. Pelna kolejka = paczka przepada, spolki do odswiezenia. */
    @Override
    public void publish(List<EngineEvent> batch) {
        if (!queue.offer(batch)) {
            batch.forEach(event -> changed.add(event.symbol()));
            if (dropped.getAndIncrement() % 1000 == 0) {
                LOG.log(System.Logger.Level.WARNING, "market data queue full, dropped batches: " + dropped.get());
            }
        }
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Przetwarza paczke: zaznacza zmienione spolki i rozsyla transakcje. Bledy nie wychodza. */
    void process(List<EngineEvent> batch) {
        for (EngineEvent event : batch) {
            changed.add(event.symbol());
            try {
                if (event.event() instanceof BookEvent.Trade trade) {
                    broadcast(event.symbol(), MarketDataMessages.trade(event, trade));
                }
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "market data broadcast failed for " + event.symbol(), e);
            }
        }
    }

    long droppedBatches() {
        return dropped.get();
    }

    void subscribe(String symbol, ClientConnection connection) {
        subscribers.computeIfAbsent(symbol, s -> ConcurrentHashMap.newKeySet()).add(connection);
    }

    void unsubscribe(ClientConnection connection) {
        subscribers.values().forEach(set -> set.remove(connection));
    }

    /** Zamkniete polaczenia wypadaja przy okazji -- nawet gdy nikt nie zawolal unsubscribe. */
    boolean hasSubscribers(String symbol) {
        Set<ClientConnection> set = subscribers.get(symbol);
        if (set == null) {
            return false;
        }
        set.removeIf(connection -> !connection.isOpen());
        return !set.isEmpty();
    }

    /** Oddaje spolki zmienione od ostatniego wywolania i zapomina o nich. */
    Set<String> drainChanged() {
        Set<String> drained = new HashSet<>();
        for (Iterator<String> it = changed.iterator(); it.hasNext(); ) {
            drained.add(it.next());
            it.remove();
        }
        return drained;
    }

    /** Wraca spolke na liste zmienionych -- np. gdy obraz arkusza nie dal sie pobrac. */
    void markChanged(String symbol) {
        changed.add(symbol);
    }

    void broadcast(String symbol, String message) {
        Set<ClientConnection> set = subscribers.getOrDefault(symbol, Set.of());
        for (ClientConnection connection : set) {
            if (connection.isOpen()) {
                connection.send(message);
            } else {
                set.remove(connection);
            }
        }
    }

    private void run() {
        while (running) {
            try {
                List<EngineEvent> batch = queue.poll(100, TimeUnit.MILLISECONDS);
                if (batch != null) {
                    process(batch);
                }
            } catch (InterruptedException e) {
                // close() -- petla sprawdzi running
            }
        }
    }
}
