package io.github.sitkowski01.exchange.engine;

import io.github.sitkowski01.exchange.domain.BookEvent;
import io.github.sitkowski01.exchange.domain.BookEvent.OrderCancelled;
import io.github.sitkowski01.exchange.domain.OrderBook;
import io.github.sitkowski01.exchange.domain.OrderRequest;
import io.github.sitkowski01.exchange.domain.OrderType;
import io.github.sitkowski01.exchange.domain.Side;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * Silnik jednego instrumentu: wlasny watek, ograniczona kolejka polecen i arkusz,
 * ktorego dotyka wylacznie ten watek.
 *
 * <p>Wiele watkow wrzuca polecenia do kolejki, jeden je wykonuje -- po kolei. Arkusz nie
 * potrzebuje wiec blokad, a kolejnosc przetwarzania jest jednoznaczna: to kolejnosc
 * w kolejce. Ten sam model stoi za LMAX Disruptor.
 *
 * <p>Id zlecen nadaje watek silnika, nie watek wolajacy. Gdyby nadawal je wolajacy,
 * dwa rownolegle zgloszenia moglyby wejsc do kolejki w innej kolejnosci niz numeracja,
 * a arkusz odrzuca id, ktore nie rosna.
 *
 * <p>Odpowiedzi sa konczone na watku silnika. Kto podepnie pod nie synchroniczny callback
 * ({@code thenApply} itp.), wykona go na tym watku i zatrzyma instrument -- wolajacy
 * powinien czekac ({@code join}) albo uzyc wariantow {@code *Async}.
 */
public final class InstrumentEngine implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(InstrumentEngine.class.getName());
    private static final Runnable STOP = () -> {
    };

    private final String symbol;
    private final BlockingQueue<Runnable> queue;
    private final Clock clock;
    private final EventSink sink;
    private final Thread thread;

    private final Object lifecycle = new Object();
    private boolean accepting = true;

    // Stan ponizej nalezy wylacznie do watku silnika.
    private final OrderBook book = new OrderBook();
    private long sequence;

    private InstrumentEngine(String symbol, int queueCapacity, Clock clock, EventSink sink) {
        this.symbol = Objects.requireNonNull(symbol, "symbol");
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.thread = Thread.ofPlatform().name("engine-" + symbol).unstarted(this::run);
    }

    public static InstrumentEngine start(String symbol, int queueCapacity, Clock clock, EventSink sink) {
        InstrumentEngine engine = new InstrumentEngine(symbol, queueCapacity, clock, sink);
        engine.thread.start();
        return engine;
    }

    public String symbol() {
        return symbol;
    }

    public CompletableFuture<OrderResult> place(Side side, OrderType type, long price, long quantity) {
        return submit(() -> {
            // Czas i walidacja przed zmiana arkusza: jesli cokolwiek tu padnie,
            // arkusz zostaje nietkniety, a klient dostaje blad zgodny z prawda.
            Instant now = clock.instant();
            OrderRequest order = new OrderRequest(book.lastOrderId() + 1, side, type, price, quantity);
            List<BookEvent> events = book.submit(order);
            publish(events, now);
            return new OrderResult(order.id(), events);
        });
    }

    public CompletableFuture<Optional<OrderCancelled>> cancel(long orderId) {
        return submit(() -> {
            Instant now = clock.instant();
            Optional<OrderCancelled> cancelled = book.cancel(orderId);
            cancelled.ifPresent(event -> publish(List.of(event), now));
            return cancelled;
        });
    }

    /** Obraz arkusza idzie przez te sama kolejke, wiec nigdy nie trafi w polowe zlecenia. */
    public CompletableFuture<BookSnapshot> snapshot(int levels) {
        return submit(() -> new BookSnapshot(
                symbol, sequence, book.depth(Side.BUY, levels), book.depth(Side.SELL, levels)));
    }

    /**
     * Przestaje przyjmowac polecenia, wykonuje te, ktore juz czekaja, i czeka na koniec watku.
     */
    @Override
    public void close() {
        synchronized (lifecycle) {
            if (!accepting) {
                return;
            }
            accepting = false;
        }
        try {
            queue.put(STOP);
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    boolean isAccepting() {
        synchronized (lifecycle) {
            return accepting;
        }
    }

    private <T> CompletableFuture<T> submit(Callable<T> command) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                result.complete(command.call());
            } catch (Throwable e) {
                // Takze Error: watek silnika nie moze umrzec po cichu, bo kazde polecenie
                // w kolejce czekaloby na odpowiedz w nieskonczonosc.
                result.completeExceptionally(e);
            }
        };
        // Sprawdzenie i wstawienie pod jednym zamkiem: inaczej polecenie mogloby wejsc
        // do kolejki za STOP i nigdy nie dostac odpowiedzi. offer() nie blokuje,
        // wiec zamek jest trzymany przez ulamek mikrosekundy.
        synchronized (lifecycle) {
            if (!accepting) {
                return CompletableFuture.failedFuture(new EngineRejectedException(symbol + ": engine closed"));
            }
            if (!queue.offer(task)) {
                return CompletableFuture.failedFuture(new EngineRejectedException(symbol + ": queue full"));
            }
        }
        return result;
    }

    private void run() {
        while (true) {
            Runnable task;
            try {
                task = queue.take();
            } catch (InterruptedException e) {
                // Watek nalezy do silnika i konczy sie wylacznie przez STOP -- tylko wtedy
                // kazde przyjete polecenie dostaje odpowiedz. Obce przerwanie ignorujemy.
                continue;
            }
            if (task == STOP) {
                return;
            }
            task.run();
        }
    }

    private void publish(List<BookEvent> events, Instant now) {
        List<EngineEvent> batch = new ArrayList<>(events.size());
        for (BookEvent event : events) {
            batch.add(new EngineEvent(symbol, ++sequence, now, event));
        }
        try {
            sink.publish(batch);
        } catch (Throwable e) {
            // Arkusz juz sie zmienil i tego nie cofniemy. Trwalosc zdarzen zapewni
            // dopiero outbox (etap 5); do tego czasu blad odbiorcy nie zatrzymuje gieldy.
            LOG.log(System.Logger.Level.ERROR, symbol + ": event sink failed at sequence " + sequence, e);
        }
    }
}
