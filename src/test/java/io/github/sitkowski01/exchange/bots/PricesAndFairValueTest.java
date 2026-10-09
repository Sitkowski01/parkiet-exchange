package io.github.sitkowski01.exchange.bots;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricesAndFairValueTest {

    @Test
    void marketPriceWinsThenFallbackThenNothing() throws Exception {
        ReferencePriceSource market = symbol -> "CDR".equals(symbol) ? OptionalLong.of(26300) : OptionalLong.empty();

        Map<String, Long> prices = StartingPrices.resolve(List.of("CDR", "PKO", "XYZ"), market,
                Map.of("CDR", new BigDecimal("1.00"), "PKO", new BigDecimal("113.94")));

        assertThat(prices).containsExactly(Map.entry("CDR", 26300L), Map.entry("PKO", 11394L));
    }

    @Test
    void throwingSourceCountsAsNoPrice() throws Exception {
        ReferencePriceSource broken = symbol -> {
            throw new IllegalStateException("boom");
        };

        assertThat(StartingPrices.resolve(List.of("CDR"), broken, Map.of("CDR", new BigDecimal("2.50"))))
                .containsExactly(Map.entry("CDR", 250L));
    }

    @Test
    void sameSeedGivesSamePath() {
        FairValue a = new FairValue(26300, 0.01, new SplittableRandom(42));
        FairValue b = new FairValue(26300, 0.01, new SplittableRandom(42));
        for (int i = 0; i < 100; i++) {
            assertThat(a.next()).isEqualTo(b.next());
        }
    }

    /** Krok jest procentowy i uciety na 4 sigma: przy 1% na krok cena nie skoczy o wiecej niz ~4%. */
    @Test
    void stepsAreBoundedAndPriceStaysPositive() {
        FairValue fair = new FairValue(5000, 0.01, new SplittableRandom(7));
        long previous = 5000;
        for (int i = 0; i < 10_000; i++) {
            long next = fair.next();
            assertThat(next).isPositive();
            assertThat(Math.abs(Math.log((double) next / previous))).isLessThan(0.045);
            previous = next;
        }
    }

    @Test
    void walkActuallyMoves() {
        FairValue fair = new FairValue(26300, 0.01, new SplittableRandom(1));
        long first = fair.next();
        boolean moved = false;
        for (int i = 0; i < 50 && !moved; i++) {
            moved = fair.next() != first;
        }
        assertThat(moved).isTrue();
    }

    @Test
    void priceNeverDropsBelowOneGrosz() {
        FairValue fair = new FairValue(1, 0.05, new SplittableRandom(3));
        for (int i = 0; i < 1000; i++) {
            assertThat(fair.next()).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void startPriceMustBePositive() {
        assertThatThrownBy(() -> new FairValue(0, 0.01, new SplittableRandom()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
