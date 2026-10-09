package io.github.sitkowski01.exchange.bots;

import io.github.sitkowski01.exchange.domain.BookEvent;
import io.github.sitkowski01.exchange.engine.InstrumentEngine;
import io.github.sitkowski01.exchange.engine.OrderResult;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Zlecenia bota, ktore moga czekac w arkuszu -- zeby dalo sie je pozniej anulowac.
 *
 * <p>Haczyk: zlecenie, na ktore nie doczekalismy sie odpowiedzi (timeout), i tak wykona sie
 * pozniej na watku silnika. Gdyby bot o nim zapomnial, wisialoby w arkuszu na zawsze. Dlatego
 * takie odpowiedzi czekaja w {@code late} i sa dopisywane, gdy w koncu przyjda.
 */
final class RestingOrders {

    private final InstrumentEngine engine;
    private final Duration timeout;
    private final Deque<Long> ids = new ArrayDeque<>();
    private final List<CompletableFuture<OrderResult>> late = new ArrayList<>();

    RestingOrders(InstrumentEngine engine, Duration timeout) {
        this.engine = engine;
        this.timeout = timeout;
    }

    /** Czeka na odpowiedz; jesli zlecenie zostalo w arkuszu, zapamietuje je. */
    void track(CompletableFuture<OrderResult> placed) throws Exception {
        collectLate();
        try {
            remember(placed.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            late.add(placed);
            throw e;
        }
    }

    /** Anuluje najstarsze zlecenie. Id znika dopiero po udanym anulowaniu -- odrzucone sprobujemy znowu. */
    void cancelOldest() throws Exception {
        Long oldest = ids.peekFirst();
        if (oldest != null) {
            engine.cancel(oldest).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            ids.removeFirst();
        }
    }

    int size() {
        return ids.size();
    }

    List<Long> ids() {
        return List.copyOf(ids);
    }

    private void collectLate() {
        for (Iterator<CompletableFuture<OrderResult>> it = late.iterator(); it.hasNext(); ) {
            CompletableFuture<OrderResult> placed = it.next();
            if (placed.isDone()) {
                it.remove();
                if (!placed.isCompletedExceptionally()) {
                    remember(placed.join());
                }
            }
        }
    }

    private void remember(OrderResult result) {
        boolean rests = result.events().stream()
                .anyMatch(e -> e instanceof BookEvent.OrderRested r && r.orderId() == result.orderId());
        if (rests) {
            ids.addLast(result.orderId());
        }
    }
}
