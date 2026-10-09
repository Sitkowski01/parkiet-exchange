package io.github.sitkowski01.exchange.bots;

import io.github.sitkowski01.exchange.domain.OrderType;
import io.github.sitkowski01.exchange.domain.Side;
import io.github.sitkowski01.exchange.engine.InstrumentEngine;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

/**
 * Animator rynku jednej spolki: trzyma w arkuszu zlecenie kupna i sprzedazy wokol wartosci
 * godziwej. Zarabia na rozstepie (kupuje taniej, sprzedaje drozej), a w zamian daje plynnosc --
 * gracz z MARKET prawie zawsze ma z kim handlowac.
 *
 * <p>Co tick przestawia kwotowania po jednej stronie naraz, wiec druga strona stoi caly czas.
 * Najpierw te, od ktorej cena ucieka: gdy wartosc rosnie, najpierw sprzedaz (w gore), potem
 * kupno -- inaczej nowe kupno mogloby trafic na stara sprzedaz i bot handlowalby sam ze soba.
 */
final class MarketMaker {

    private final InstrumentEngine engine;
    private final int spreadBps;
    private final long quantity;
    private final RestingOrders bids;
    private final RestingOrders asks;
    private long lastFair;

    /** @param spreadBps rozstep w punktach bazowych (1 pb = 0,01%), np. 20 = 0,2% */
    MarketMaker(InstrumentEngine engine, int spreadBps, long quantity, Duration timeout) {
        this.engine = engine;
        this.spreadBps = spreadBps;
        this.quantity = quantity;
        this.bids = new RestingOrders(engine, timeout);
        this.asks = new RestingOrders(engine, timeout);
    }

    void requote(long fair) throws Exception {
        long half = Math.max(1, fair * spreadBps / 20_000);
        if (fair >= lastFair) {
            replace(asks, Side.SELL, fair + half);
            replace(bids, Side.BUY, Math.max(1, fair - half));
        } else {
            replace(bids, Side.BUY, Math.max(1, fair - half));
            replace(asks, Side.SELL, fair + half);
        }
        lastFair = fair;
    }

    /** Id zlecen bota, ktore moga jeszcze czekac w arkuszu. */
    List<Long> resting() {
        return Stream.concat(bids.ids().stream(), asks.ids().stream()).toList();
    }

    private void replace(RestingOrders side, Side direction, long price) throws Exception {
        // Zlecenie juz zrealizowane po prostu nie da sie anulowac -- silnik zwraca pusty wynik.
        while (side.size() > 0) {
            side.cancelOldest();
        }
        side.track(engine.place(direction, OrderType.LIMIT, price, quantity));
    }
}
