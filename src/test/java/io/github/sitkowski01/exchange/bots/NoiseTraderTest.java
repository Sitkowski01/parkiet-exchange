package io.github.sitkowski01.exchange.bots;

import io.github.sitkowski01.exchange.domain.OrderType;
import io.github.sitkowski01.exchange.domain.PriceLevel;
import io.github.sitkowski01.exchange.domain.Side;
import io.github.sitkowski01.exchange.engine.BookSnapshot;
import io.github.sitkowski01.exchange.engine.EventSink;
import io.github.sitkowski01.exchange.engine.MatchingEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;

/** Gracz losowy z "ustawionym" losowaniem: kazda decyzja jest zaplanowana, wiec wynik jest pewny. */
@Timeout(10)
class NoiseTraderTest {

    private final MatchingEngine engine = new MatchingEngine(List.of("CDR"), 1000, Clock.systemUTC(), EventSink.NONE);

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    void marketOrderTakesLiquidity() throws Exception {
        engine.instrument("CDR").place(Side.SELL, OrderType.LIMIT, 10000, 10).join();
        // kupno, ilosc 4+1, losowanie 0.1 < 0.7 -> MARKET
        trader(new Scripted().bool(true).nums(4).fractions(0.1)).act(10000);

        assertThat(book().asks()).containsExactly(new PriceLevel(10000, 5, 1));
    }

    @Test
    void limitBuyRestsBelowFairValue() throws Exception {
        // kupno, ilosc 3, 0.9 -> LIMIT, odstep (9+1) pb z 100 zl = 10 gr
        NoiseTrader trader = trader(new Scripted().bool(true).nums(2, 9).fractions(0.9));
        trader.act(10000);

        assertThat(book().bids()).containsExactly(new PriceLevel(9990, 3, 1));
        assertThat(trader.restingCount()).isEqualTo(1);
    }

    @Test
    void limitSellRestsAboveFairValue() throws Exception {
        trader(new Scripted().bool(false).nums(0, 49).fractions(0.9)).act(10000);

        assertThat(book().asks()).containsExactly(new PriceLevel(10050, 1, 1));
    }

    @Test
    void offsetIsAtLeastOneGrosz() throws Exception {
        trader(new Scripted().bool(true).nums(0, 0).fractions(0.9)).act(100);

        assertThat(book().bids()).extracting(PriceLevel::price).containsExactly(99L);
    }

    @Test
    void limitThatTradesIsNotTracked() throws Exception {
        engine.instrument("CDR").place(Side.SELL, OrderType.LIMIT, 9000, 10).join();
        NoiseTrader trader = trader(new Scripted().bool(true).nums(0, 0).fractions(0.9));
        trader.act(10000);

        assertThat(trader.restingCount()).isZero();
    }

    @Test
    void oldestLimitIsCancelledOverTheCap() throws Exception {
        Scripted random = new Scripted();
        for (int i = 0; i < NoiseTrader.MAX_RESTING + 1; i++) {
            random.bool(true).nums(0, i).fractions(0.9);
        }
        NoiseTrader trader = trader(random);
        for (int i = 0; i < NoiseTrader.MAX_RESTING + 1; i++) {
            trader.act(100_000);
        }

        assertThat(trader.restingCount()).isEqualTo(NoiseTrader.MAX_RESTING);
        // Pierwsze zlecenie (odstep 1 pb = 99 990) zostalo anulowane, reszta czeka.
        assertThat(book().bids()).extracting(PriceLevel::price).doesNotContain(99_990L).hasSize(20);
    }

    @Test
    void noiseProbabilityGatesTheTrader() {
        BotRunner never = runner(0.0);
        BotRunner always = runner(1.0);
        never.tick();
        assertThat(orders()).as("tylko dwa kwotowania animatora").isEqualTo(2);
        always.tick();
        assertThat(orders()).as("drugi animator (2) i jedno zlecenie gracza").isEqualTo(5);
    }

    private BotRunner runner(double probability) {
        return new BotRunner(engine, Map.of("CDR", 10000L),
                new BotRunner.BotSettings(0.0, 20, 50, probability, Duration.ofSeconds(2)),
                () -> new Scripted().bool(true).nums(0, 0).fractions(0.5, 0.9));
    }

    private int orders() {
        BookSnapshot book = book();
        return book.bids().stream().mapToInt(PriceLevel::orders).sum()
                + book.asks().stream().mapToInt(PriceLevel::orders).sum();
    }

    private NoiseTrader trader(RandomGenerator random) {
        return new NoiseTrader(engine.instrument("CDR"), random, Duration.ofSeconds(2));
    }

    private BookSnapshot book() {
        return engine.instrument("CDR").snapshot(100).join();
    }

    /** Losowanie z gory zaplanowane; po wyczerpaniu planu zwraca zera. */
    static final class Scripted implements RandomGenerator {

        private final Deque<Boolean> bools = new ArrayDeque<>();
        private final Deque<Integer> ints = new ArrayDeque<>();
        private final Deque<Double> doubles = new ArrayDeque<>();

        Scripted bool(boolean value) {
            bools.add(value);
            return this;
        }

        Scripted nums(int... values) {
            for (int v : values) {
                ints.add(v);
            }
            return this;
        }

        Scripted fractions(double... values) {
            for (double v : values) {
                doubles.add(v);
            }
            return this;
        }

        @Override
        public boolean nextBoolean() {
            return bools.isEmpty() || bools.poll();
        }

        @Override
        public int nextInt(int bound) {
            return ints.isEmpty() ? 0 : ints.poll();
        }

        @Override
        public double nextDouble() {
            return doubles.isEmpty() ? 0.0 : doubles.poll();
        }

        @Override
        public double nextGaussian() {
            return 0.0;
        }

        @Override
        public long nextLong() {
            return 0;
        }
    }
}
