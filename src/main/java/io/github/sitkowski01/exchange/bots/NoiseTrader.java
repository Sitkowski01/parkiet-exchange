package io.github.sitkowski01.exchange.bots;

import io.github.sitkowski01.exchange.domain.OrderType;
import io.github.sitkowski01.exchange.domain.Side;
import io.github.sitkowski01.exchange.engine.InstrumentEngine;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;

/**
 * Gracz losowy: nie ma zdania o cenie, kupuje albo sprzedaje na chybil trafil. Wiekszosc jego
 * zlecen to MARKET (zbija kwotowania animatora -- powstaja transakcje), reszta to LIMIT troche
 * obok wartosci godziwej (arkusz ma glebokosc, a nie tylko dwa poziomy animatora).
 *
 * <p>Trzyma w arkuszu najwyzej {@link #MAX_RESTING} zlecen -- nadmiar anuluje od najstarszego.
 * Inaczej arkusz rosnalby bez konca, bo cena ucieka, a stare zlecenia zostaja.
 */
final class NoiseTrader {

    static final int MAX_RESTING = 20;
    private static final double MARKET_SHARE = 0.7;
    /** LIMIT najwyzej o tyle od wartosci godziwej: 50 pb = 0,5%. */
    private static final int LIMIT_OFFSET_BPS = 50;
    private static final int MAX_QUANTITY = 10;

    private final InstrumentEngine engine;
    private final RandomGenerator random;
    private final Duration timeout;
    private final RestingOrders resting;

    NoiseTrader(InstrumentEngine engine, RandomGenerator random, Duration timeout) {
        this.engine = engine;
        this.random = random;
        this.timeout = timeout;
        this.resting = new RestingOrders(engine, timeout);
    }

    void act(long fair) throws Exception {
        Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
        long quantity = 1 + random.nextInt(MAX_QUANTITY);
        if (random.nextDouble() < MARKET_SHARE) {
            engine.place(side, OrderType.MARKET, 0, quantity).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return;
        }
        // Kupujacy celuje ponizej wartosci, sprzedajacy powyzej -- zlecenie zostaje w arkuszu.
        long offset = Math.max(1, fair * (1 + random.nextInt(LIMIT_OFFSET_BPS)) / 10_000);
        long price = side == Side.BUY ? Math.max(1, fair - offset) : fair + offset;
        resting.track(engine.place(side, OrderType.LIMIT, price, quantity));
        while (resting.size() > MAX_RESTING) {
            resting.cancelOldest();
        }
    }

    int restingCount() {
        return resting.size();
    }
}
