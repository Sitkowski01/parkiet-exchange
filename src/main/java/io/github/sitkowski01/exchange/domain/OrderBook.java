package io.github.sitkowski01.exchange.domain;

import io.github.sitkowski01.exchange.domain.BookEvent.OrderCancelled;
import io.github.sitkowski01.exchange.domain.BookEvent.OrderRested;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * Arkusz zlecen jednego instrumentu z kojarzeniem wedlug ceny, a potem czasu
 * (price-time priority).
 *
 * <p>Najpierw wygrywa lepsza cena: najwyzsza oferta kupna, najnizsza sprzedazy.
 * Przy tej samej cenie wygrywa zlecenie, ktore przyszlo wczesniej.
 *
 * <p>Klasa celowo nie jest bezpieczna watkowo. Silnik obsluguje kazdy arkusz
 * z jednego watku, wiec blokady bylyby czystym kosztem.
 */
public final class OrderBook {

    private final NavigableMap<Long, Level> bids = new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<Long, Level> asks = new TreeMap<>();
    private final Map<Long, RestingOrder> index = new HashMap<>();
    private long lastOrderId;

    public List<BookEvent> submit(OrderRequest order) {
        if (order.id() <= lastOrderId) {
            throw new IllegalArgumentException(
                    "order id must increase: got " + order.id() + " after " + lastOrderId);
        }
        lastOrderId = order.id();

        List<BookEvent> events = new ArrayList<>();
        long remaining = match(order, events);
        if (remaining > 0) {
            if (order.type() == OrderType.MARKET) {
                events.add(new OrderCancelled(order.id(), remaining, OrderCancelled.Reason.NO_LIQUIDITY));
            } else {
                rest(order, remaining);
                events.add(new OrderRested(order.id(), order.side(), order.price(), remaining));
            }
        }
        return events;
    }

    /** Anuluje zlecenie czekajace w arkuszu. Puste, gdy go tam nie ma (zrealizowane, anulowane, nieznane). */
    public Optional<OrderCancelled> cancel(long orderId) {
        RestingOrder order = index.remove(orderId);
        if (order == null) {
            return Optional.empty();
        }
        NavigableMap<Long, Level> side = sideOf(order.side);
        Level level = side.get(order.price);
        level.remove(order);
        if (level.isEmpty()) {
            side.remove(order.price);
        }
        return Optional.of(new OrderCancelled(orderId, order.remaining, OrderCancelled.Reason.REQUESTED));
    }

    /** Id ostatniego przyjetego zlecenia; nastepne musi byc wieksze. 0, gdy jeszcze zadnego nie bylo. */
    public long lastOrderId() {
        return lastOrderId;
    }

    public OptionalLong bestBid() {
        return bids.isEmpty() ? OptionalLong.empty() : OptionalLong.of(bids.firstKey());
    }

    public OptionalLong bestAsk() {
        return asks.isEmpty() ? OptionalLong.empty() : OptionalLong.of(asks.firstKey());
    }

    /** Najlepsze {@code levels} poziomow danej strony, od najlepszej ceny. */
    public List<PriceLevel> depth(Side side, int levels) {
        if (levels < 0) {
            throw new IllegalArgumentException("levels must not be negative, was " + levels);
        }
        return sideOf(side).values().stream().limit(levels).map(Level::view).toList();
    }

    private long match(OrderRequest taker, List<BookEvent> events) {
        NavigableMap<Long, Level> opposite = taker.side() == Side.BUY ? asks : bids;
        long remaining = taker.quantity();
        while (remaining > 0) {
            Map.Entry<Long, Level> best = opposite.firstEntry();
            if (best == null || !crosses(taker, best.getKey())) {
                break;
            }
            Level level = best.getValue();
            remaining = level.fill(taker.id(), taker.side(), remaining, events, index::remove);
            if (level.isEmpty()) {
                opposite.pollFirstEntry();
            }
        }
        return remaining;
    }

    private static boolean crosses(OrderRequest taker, long bestOppositePrice) {
        if (taker.type() == OrderType.MARKET) {
            return true;
        }
        return taker.side() == Side.BUY
                ? bestOppositePrice <= taker.price()
                : bestOppositePrice >= taker.price();
    }

    private void rest(OrderRequest order, long remaining) {
        RestingOrder resting = new RestingOrder(order.id(), order.side(), order.price(), remaining);
        sideOf(order.side()).computeIfAbsent(order.price(), Level::new).add(resting);
        index.put(order.id(), resting);
    }

    private NavigableMap<Long, Level> sideOf(Side side) {
        return side == Side.BUY ? bids : asks;
    }
}
