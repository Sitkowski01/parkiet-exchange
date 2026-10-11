package io.github.sitkowski01.exchange.engine;

import io.github.sitkowski01.exchange.domain.BookEvent;
import io.github.sitkowski01.exchange.domain.OrderType;
import io.github.sitkowski01.exchange.domain.Side;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pamiec powtorek: wyniki ostatnich zlecen spolki po {@link ClientOrderId}.
 *
 * <p>Okno jest ograniczone ({@code capacity} ostatnich zlecen z identyfikatorem) -- inaczej pamiec
 * rosnie przez caly dzien handlu. Powtorka ma sens tuz po timeoucie, nie po stu tysiacach zlecen.
 *
 * <p>Jak arkusz: nalezy do watku silnika i nie jest bezpieczna watkowo.
 */
public final class ClientOrders {

    /** Ile ostatnich zlecen z identyfikatorem pamieta jedna spolka. */
    public static final int DEFAULT_CAPACITY = 100_000;

    /** Parametry zlecenia, ktorymi powtorka musi sie zgadzac z oryginalem. */
    public record Spec(Side side, OrderType type, long price, long quantity) {
    }

    /** @param spec {@code null} po odbudowie z dziennika -- parametrow zlecenia dziennik nie przechowuje */
    private record Remembered(Spec spec, OrderResult result) {
    }

    private final int capacity;
    private final Map<ClientOrderId, Remembered> placed;

    public ClientOrders(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, was " + capacity);
        }
        this.capacity = capacity;
        // Kolejnosc wstawiania (nie dostepu): okno to ostatnie zlozone zlecenia, a powtorka go nie przesuwa.
        this.placed = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ClientOrderId, Remembered> eldest) {
                return size() > ClientOrders.this.capacity;
            }
        };
    }

    public static ClientOrders empty() {
        return new ClientOrders(DEFAULT_CAPACITY);
    }

    /**
     * Odbudowa z dziennika: zdarzenia z identyfikatorem w kolejnosci {@code sequence}. Zdarzenia jednego
     * zlecenia leza w dzienniku jedno po drugim (silnik publikuje je jedna paczka), a pierwsze z nich
     * zawsze dotyczy samego zlecenia -- stad jego id. Gdy identyfikator wrocil po wypadnieciu z okna,
     * wygrywa pozniejsze zlecenie.
     */
    public static ClientOrders rebuild(int capacity, Iterable<EngineEvent> journal) {
        ClientOrders orders = new ClientOrders(capacity);
        ClientOrderId current = null;
        List<BookEvent> events = new ArrayList<>();
        for (EngineEvent event : journal) {
            if (event.clientOrderId() == null) {
                throw new IllegalArgumentException("event " + event.sequence() + " has no client order id");
            }
            if (!event.clientOrderId().equals(current)) {
                orders.rememberRebuilt(current, events);
                current = event.clientOrderId();
                events = new ArrayList<>();
            }
            events.add(event.event());
        }
        orders.rememberRebuilt(current, events);
        return orders;
    }

    /**
     * Pierwotny wynik, gdy to powtorka; pusto, gdy identyfikator jest nowy.
     *
     * @throws ClientOrderIdConflictException gdy identyfikator jest znany, ale parametry sie roznia --
     *                                        to nie powtorka, tylko pomylka klienta
     */
    public Optional<OrderResult> repeatOf(ClientOrderId id, Spec spec) {
        Remembered remembered = placed.get(id);
        if (remembered == null) {
            return Optional.empty();
        }
        if (remembered.spec() != null && !remembered.spec().equals(spec)) {
            throw new ClientOrderIdConflictException(id);
        }
        return Optional.of(remembered.result().asRepeat());
    }

    public void remember(ClientOrderId id, Spec spec, OrderResult result) {
        // Najpierw usuniecie: identyfikator uzyty ponownie trafia na koniec okna, jak kazde nowe zlecenie.
        placed.remove(id);
        placed.put(id, new Remembered(spec, result));
    }

    public int size() {
        return placed.size();
    }

    private void rememberRebuilt(ClientOrderId id, List<BookEvent> events) {
        if (id != null) {
            remember(id, null, new OrderResult(orderIdOf(events.getFirst()), events));
        }
    }

    private static long orderIdOf(BookEvent event) {
        return switch (event) {
            case BookEvent.OrderRested r -> r.orderId();
            case BookEvent.Trade t -> t.takerOrderId();
            case BookEvent.OrderCancelled c -> c.orderId();
        };
    }
}
