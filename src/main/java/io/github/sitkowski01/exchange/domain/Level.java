package io.github.sitkowski01.exchange.domain;

import io.github.sitkowski01.exchange.domain.BookEvent.Trade;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Kolejka zlecen czekajacych po jednej cenie.
 *
 * <p>LinkedHashMap zamiast listy: zachowuje kolejnosc wstawiania (czyli priorytet
 * czasowy), a jednoczesnie usuwa zlecenie po id w O(1). Na liscie anulowanie
 * zlecenia ze srodka kolejki kosztowaloby O(n).
 */
final class Level {

    private final long price;
    private final Map<Long, RestingOrder> queue = new LinkedHashMap<>();
    private long quantity;

    Level(long price) {
        this.price = price;
    }

    void add(RestingOrder order) {
        queue.put(order.id, order);
        quantity += order.remaining;
    }

    void remove(RestingOrder order) {
        queue.remove(order.id);
        quantity -= order.remaining;
    }

    /**
     * Kojarzy przychodzace zlecenie z kolejka, od najstarszego.
     *
     * @param onMakerFilled dostaje id kazdego zlecenia z kolejki, ktore zostalo w calosci zrealizowane
     * @return ile z przychodzacego zlecenia zostalo po przejsciu przez ten poziom
     */
    long fill(long takerId, Side takerSide, long wanted, List<BookEvent> events, LongConsumer onMakerFilled) {
        Iterator<RestingOrder> it = queue.values().iterator();
        while (wanted > 0 && it.hasNext()) {
            RestingOrder maker = it.next();
            long traded = Math.min(wanted, maker.remaining);
            maker.remaining -= traded;
            quantity -= traded;
            wanted -= traded;
            events.add(new Trade(maker.id, takerId, takerSide, price, traded));
            if (maker.remaining == 0) {
                it.remove();
                onMakerFilled.accept(maker.id);
            }
        }
        return wanted;
    }

    /** Odtwarzanie z dziennika: realizacja konkretnego zlecenia, zapisana wczesniej jako transakcja. */
    void reduce(RestingOrder order, long traded) {
        order.remaining -= traded;
        quantity -= traded;
        if (order.remaining == 0) {
            queue.remove(order.id);
        }
    }

    /** Zlecenia od najstarszego -- do migawki arkusza. */
    void forEach(Consumer<RestingOrder> action) {
        queue.values().forEach(action);
    }

    boolean isEmpty() {
        return queue.isEmpty();
    }

    PriceLevel view() {
        return new PriceLevel(price, quantity, queue.size());
    }
}
