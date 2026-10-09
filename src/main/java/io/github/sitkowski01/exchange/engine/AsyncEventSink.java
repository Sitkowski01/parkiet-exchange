package io.github.sitkowski01.exchange.engine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Zdejmuje wolnego odbiorce (np. zapis do bazy) z watku silnika.
 *
 * <p>Silnik wrzuca paczke do ograniczonej kolejki i od razu wraca do kojarzenia. Osobny watek
 * zbiera wszystko, co sie nazbieralo, i oddaje odbiorcy jednym wywolaniem (group commit):
 * 50 polecen to jedna transakcja w bazie, a nie 50.
 *
 * <p>Blad odbiorcy jest ponawiany z rosnaca przerwa -- do skutku, wiec odbiorca musi byc
 * idempotentny (ponowienie po niejasnym bledzie moze zapisac to samo drugi raz). Gdy kolejka
 * sie zapelni, {@link #publish} czeka (backpressure): gielda staje, zamiast handlowac bez
 * dziennika. Tak samo przy paczce, ktorej odbiorca nigdy nie przyjmie -- dziura w numeracji
 * dziennika bylaby gorsza niz zatrzymanie.
 *
 * <p>Zamykanie ma limit czasu liczony od {@link #beginShutdown}: po nim nikt juz na nic nie czeka,
 * a niezapisane zdarzenia ida do logu jako strata. Inaczej martwa baza nie dalaby zamknac aplikacji.
 *
 * <p>Kolejnosc jest zachowana: jedna kolejka FIFO i jeden watek piszacy.
 */
public final class AsyncEventSink implements EventSink, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(AsyncEventSink.class.getName());
    private static final int MAX_BATCHES_PER_WRITE = 256;
    private static final int MAX_BACKOFF_MULTIPLIER = 32;
    private static final long NO_DEADLINE = Long.MAX_VALUE;

    private final EventSink delegate;
    private final BlockingQueue<List<EngineEvent>> queue;
    private final Duration retryBackoff;
    private final Duration closeTimeout;
    private final Thread thread;

    // stopped zmienia sie i jest sprawdzane pod tym zamkiem razem ze wstawieniem do kolejki:
    // po stopped = true nic juz do kolejki nie wejdzie, wiec watek piszacy wie, kiedy skonczyl.
    private final Object lock = new Object();
    private volatile boolean stopped;
    private volatile long deadline = NO_DEADLINE;

    private AsyncEventSink(EventSink delegate, int capacity, Duration retryBackoff, Duration closeTimeout) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.retryBackoff = Objects.requireNonNull(retryBackoff, "retryBackoff");
        if (retryBackoff.toMillis() < 1) {
            // Zero dawaloby ponawianie w ciasnej petli, ktore zjada rdzen i zasypuje log.
            throw new IllegalArgumentException("retry backoff must be at least 1 ms, was " + retryBackoff);
        }
        this.closeTimeout = Objects.requireNonNull(closeTimeout, "closeTimeout");
        this.thread = Thread.ofPlatform().name("event-writer").unstarted(this::run);
    }

    /**
     * @param capacity     ile paczek moze czekac, zanim silnik zacznie czekac na zapis
     * @param retryBackoff pierwsza przerwa po bledzie odbiorcy; kazda kolejna dwa razy dluzsza (do 32x)
     * @param closeTimeout ile najwyzej trwa zamykanie, liczac od {@link #beginShutdown}
     */
    public static AsyncEventSink start(EventSink delegate, int capacity, Duration retryBackoff, Duration closeTimeout) {
        AsyncEventSink sink = new AsyncEventSink(delegate, capacity, retryBackoff, closeTimeout);
        sink.thread.start();
        return sink;
    }

    /**
     * Czeka, gdy kolejka jest pelna. Po uplywie czasu na zamkniecie -- albo po {@link #close} --
     * rzuca wyjatek zamiast czekac: silnik zaloguje strate, ale sam sie zamknie.
     */
    @Override
    public void publish(List<EngineEvent> batch) {
        while (true) {
            synchronized (lock) {
                if (stopped) {
                    throw new IllegalStateException("event writer closed, dropping " + batch.size() + " events");
                }
                if (queue.offer(batch)) {
                    return;
                }
            }
            if (pastDeadline()) {
                throw new IllegalStateException("event writer stuck on shutdown, dropping " + batch.size() + " events");
            }
            // Kolejka pelna: czekamy krotko i probujemy znowu. Bez zamka, zeby close() mogl wejsc.
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    /** Uruchamia zegar zamykania. Wolac na poczatku zamykania aplikacji, zanim zamknie sie silnik. */
    public void beginShutdown() {
        synchronized (lock) {
            if (deadline == NO_DEADLINE) {
                deadline = System.nanoTime() + closeTimeout.toNanos();
            }
        }
    }

    /** Zapisuje to, co czeka (w limicie czasu), i konczy watek. Wolac po zamknieciu silnikow. */
    @Override
    public void close() {
        beginShutdown();
        synchronized (lock) {
            stopped = true;
        }
        boolean interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        List<List<EngineEvent>> pending = new ArrayList<>();
        while (true) {
            List<EngineEvent> first = pollUninterruptibly();
            if (first == null) {
                // Najpierw stopped, potem pusta kolejka: w odwrotnej kolejnosci paczka wstawiona
                // miedzy jednym a drugim sprawdzeniem zostalaby w kolejce na zawsze.
                if (stopped && queue.isEmpty()) {
                    return;
                }
                continue;
            }
            pending.add(first);
            queue.drainTo(pending, MAX_BATCHES_PER_WRITE - 1);
            List<EngineEvent> events = new ArrayList<>();
            pending.forEach(events::addAll);
            pending.clear();
            if (!events.isEmpty()) {
                write(events);
            }
        }
    }

    private void write(List<EngineEvent> events) {
        long backoff = retryBackoff.toMillis();
        for (int attempt = 1; ; attempt++) {
            if (pastDeadline()) {
                LOG.log(System.Logger.Level.ERROR, "shutdown timeout reached, " + events.size() + " events lost");
                return;
            }
            try {
                delegate.publish(events);
                return;
            } catch (Throwable e) {
                // Pelny stos tylko raz -- godzinna awaria bazy nie moze zasypac logu.
                String message = "event write failed (attempt " + attempt + "), retrying in " + backoff + " ms";
                if (attempt == 1) {
                    LOG.log(System.Logger.Level.WARNING, message, e);
                } else {
                    LOG.log(System.Logger.Level.WARNING, message + ": " + e);
                }
                sleepUninterruptibly(backoff);
                backoff = Math.min(backoff * 2, retryBackoff.toMillis() * MAX_BACKOFF_MULTIPLIER);
            }
        }
    }

    private boolean pastDeadline() {
        long d = deadline;
        return d != NO_DEADLINE && System.nanoTime() - d > 0;
    }

    // Watek nalezy do nas i konczy sie przez stopped -- obce przerwania ignorujemy.
    private List<EngineEvent> pollUninterruptibly() {
        try {
            return queue.poll(50, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            return null;
        }
    }

    private static void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            // przerwa po bledzie moze byc krotsza, nic sie nie stanie
        }
    }
}
