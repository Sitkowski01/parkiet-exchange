package io.github.sitkowski01.exchange.bots;

import io.github.sitkowski01.exchange.domain.BookEvent;
import io.github.sitkowski01.exchange.domain.OrderType;
import io.github.sitkowski01.exchange.domain.PriceLevel;
import io.github.sitkowski01.exchange.domain.Side;
import io.github.sitkowski01.exchange.engine.BookSnapshot;
import io.github.sitkowski01.exchange.engine.MatchingEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Boty na prawdziwym silniku: arkusz, ktory zostawiaja, i transakcje, ktore robia. */
@Timeout(20)
class BotsOnEngineTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final AtomicLong trades = new AtomicLong();
    private final MatchingEngine engine = new MatchingEngine(List.of("CDR", "PKO"), 1000, Clock.systemUTC(),
            batch -> batch.forEach(e -> {
                if (e.event() instanceof BookEvent.Trade) {
                    trades.incrementAndGet();
                }
            }));

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    void makerQuotesAroundFairValueWithSpread() throws Exception {
        MarketMaker maker = new MarketMaker(engine.instrument("CDR"), 20, 50, TIMEOUT);

        maker.requote(26300);

        // 20 pb z 263 zl = 52,6 gr rozstepu, czyli 26 gr w kazda strone.
        BookSnapshot book = book("CDR");
        assertThat(book.bids()).containsExactly(new PriceLevel(26274, 50, 1));
        assertThat(book.asks()).containsExactly(new PriceLevel(26326, 50, 1));
    }

    @Test
    void requoteReplacesOldQuotesInsteadOfStackingThem() throws Exception {
        MarketMaker maker = new MarketMaker(engine.instrument("CDR"), 20, 50, TIMEOUT);
        maker.requote(26300);
        maker.requote(26400);
        maker.requote(26500);

        BookSnapshot book = book("CDR");
        assertThat(book.bids()).hasSize(1).first().extracting(PriceLevel::price).isEqualTo(26474L);
        assertThat(book.asks()).hasSize(1).first().extracting(PriceLevel::price).isEqualTo(26526L);
        assertThat(maker.resting()).hasSize(2);
    }

    /** Skok ceny wiekszy niz rozstep: nowe kupno nie moze trafic na stara sprzedaz bota (i odwrotnie). */
    @Test
    void bigMoveDoesNotMakeTheMakerTradeWithItself() throws Exception {
        MarketMaker maker = new MarketMaker(engine.instrument("CDR"), 20, 50, TIMEOUT);
        maker.requote(10000);
        maker.requote(10500);
        maker.requote(9500);

        assertThat(trades.get()).isZero();
        assertThat(book("CDR").bids()).extracting(PriceLevel::price).containsExactly(9491L);
        assertThat(book("CDR").asks()).extracting(PriceLevel::price).containsExactly(9509L);
    }

    @Test
    void tinyPriceStillHasOneGroszSpread() throws Exception {
        MarketMaker maker = new MarketMaker(engine.instrument("CDR"), 20, 50, TIMEOUT);
        maker.requote(2);

        assertThat(book("CDR").bids()).extracting(PriceLevel::price).containsExactly(1L);
        assertThat(book("CDR").asks()).extracting(PriceLevel::price).containsExactly(3L);
    }

    @Test
    void filledQuoteIsNotTrackedAsResting() throws Exception {
        MarketMaker maker = new MarketMaker(engine.instrument("CDR"), 20, 50, TIMEOUT);
        engine.instrument("CDR").place(Side.SELL, OrderType.LIMIT, 26000, 100).join();

        maker.requote(26300);

        // Kupno animatora po 262,74 trafilo na sprzedaz po 260 i od razu sie zrealizowalo.
        assertThat(maker.resting()).hasSize(1);
        assertThat(trades.get()).isEqualTo(1);
    }

    @Test
    void noiseTraderKeepsAtMostTwentyRestingOrders() throws Exception {
        NoiseTrader noise = new NoiseTrader(engine.instrument("PKO"), new SplittableRandom(5), TIMEOUT);
        for (int i = 0; i < 500; i++) {
            noise.act(11394);
        }

        assertThat(noise.restingCount()).isLessThanOrEqualTo(NoiseTrader.MAX_RESTING);
        BookSnapshot book = book("PKO");
        int orders = book.bids().stream().mapToInt(PriceLevel::orders).sum()
                + book.asks().stream().mapToInt(PriceLevel::orders).sum();
        assertThat(orders).isLessThanOrEqualTo(NoiseTrader.MAX_RESTING);
    }

    /** Kilkaset tickow: sa transakcje, arkusz ma obie strony, sie nie krzyzuje i nie puchnie. */
    @Test
    void runnerMakesALiveMarket() throws Exception {
        BotRunner runner = new BotRunner(engine, Map.of("CDR", 26300L, "PKO", 11394L),
                new BotRunner.BotSettings(0.001, 20, 50, 0.6, TIMEOUT), () -> new SplittableRandom(11));
        for (int i = 0; i < 300; i++) {
            runner.tick();
        }

        assertThat(trades.get()).isGreaterThan(50);
        for (String symbol : List.of("CDR", "PKO")) {
            BookSnapshot book = book(symbol);
            assertThat(book.bids()).isNotEmpty();
            assertThat(book.asks()).isNotEmpty();
            assertThat(book.bids().getFirst().price()).isLessThan(book.asks().getFirst().price());
            int orders = book.bids().stream().mapToInt(PriceLevel::orders).sum()
                    + book.asks().stream().mapToInt(PriceLevel::orders).sum();
            assertThat(orders).isLessThanOrEqualTo(NoiseTrader.MAX_RESTING + 2);
        }
        assertThat(runner.symbols()).containsExactlyInAnyOrder("CDR", "PKO");
    }

    @Test
    void runnerTradesInBackgroundUntilClosed() {
        BotRunner runner = new BotRunner(engine, Map.of("CDR", 26300L, "PKO", 11394L),
                new BotRunner.BotSettings(0.001, 20, 50, 1.0, TIMEOUT), SplittableRandom::new);
        assertThat(runner.isRunning()).isFalse();
        runner.close();

        runner.start(Duration.ofMillis(10));
        runner.start(Duration.ofMillis(10));
        assertThat(runner.isRunning()).isTrue();
        await().atMost(Duration.ofSeconds(10)).until(() -> trades.get() > 20);

        runner.close();
        assertThat(runner.isRunning()).isFalse();
        long after = trades.get();
        await().pollDelay(Duration.ofMillis(200)).until(() -> true);
        assertThat(trades.get()).as("po close boty juz nie handluja").isEqualTo(after);
    }

    @Test
    void tickOnClosedEngineDoesNotThrow() {
        BotRunner runner = new BotRunner(engine, Map.of("CDR", 26300L),
                new BotRunner.BotSettings(0.001, 20, 50, 1.0, TIMEOUT), () -> new SplittableRandom(1));
        engine.close();

        runner.tick();
    }

    private BookSnapshot book(String symbol) {
        return engine.instrument(symbol).snapshot(100).join();
    }
}
