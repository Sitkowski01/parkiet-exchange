package io.github.sitkowski01.exchange.domain;

import io.github.sitkowski01.exchange.domain.BookEvent.OrderRested;
import io.github.sitkowski01.exchange.domain.BookEvent.Trade;
import org.junit.jupiter.api.Test;

import static io.github.sitkowski01.exchange.domain.OrderRequest.limit;
import static io.github.sitkowski01.exchange.domain.Side.BUY;
import static io.github.sitkowski01.exchange.domain.Side.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class LimitOrderMatchingTest {

    private final OrderBook book = new OrderBook();

    @Test
    void restsWhenBookIsEmpty() {
        assertThat(book.submit(limit(1, BUY, 100, 10)))
                .containsExactly(new OrderRested(1, BUY, 100, 10));
        assertThat(book.bestBid()).hasValue(100);
        assertThat(book.bestAsk()).isEmpty();
    }

    @Test
    void doesNotTradeWhenPricesDoNotCross() {
        book.submit(limit(1, SELL, 101, 10));

        assertThat(book.submit(limit(2, BUY, 100, 10)))
                .containsExactly(new OrderRested(2, BUY, 100, 10));
        assertThat(book.bestBid()).hasValue(100);
        assertThat(book.bestAsk()).hasValue(101);
    }

    @Test
    void tradesAtEqualPrice() {
        book.submit(limit(1, SELL, 100, 10));

        assertThat(book.submit(limit(2, BUY, 100, 10)))
                .containsExactly(new Trade(1, 2, BUY, 100, 10));
        assertThat(book.bestBid()).isEmpty();
        assertThat(book.bestAsk()).isEmpty();
    }

    @Test
    void tradesAtMakerPriceNotTakerPrice() {
        book.submit(limit(1, SELL, 100, 10));
        assertThat(book.submit(limit(2, BUY, 105, 10)))
                .containsExactly(new Trade(1, 2, BUY, 100, 10));

        book.submit(limit(3, BUY, 90, 10));
        assertThat(book.submit(limit(4, SELL, 80, 10)))
                .containsExactly(new Trade(3, 4, SELL, 90, 10));
    }

    @Test
    void restsUnfilledRemainderOfTaker() {
        book.submit(limit(1, SELL, 100, 4));

        assertThat(book.submit(limit(2, BUY, 100, 10)))
                .containsExactly(new Trade(1, 2, BUY, 100, 4), new OrderRested(2, BUY, 100, 6));
        assertThat(book.depth(BUY, 1)).containsExactly(new PriceLevel(100, 6, 1));
    }

    @Test
    void leavesUnfilledRemainderOfMaker() {
        book.submit(limit(1, SELL, 100, 10));

        assertThat(book.submit(limit(2, BUY, 100, 3)))
                .containsExactly(new Trade(1, 2, BUY, 100, 3));
        assertThat(book.depth(SELL, 1)).containsExactly(new PriceLevel(100, 7, 1));
    }

    @Test
    void betterPriceWinsRegardlessOfArrival() {
        book.submit(limit(1, SELL, 102, 5));
        book.submit(limit(2, SELL, 101, 5));
        book.submit(limit(3, SELL, 103, 5));

        assertThat(book.submit(limit(4, BUY, 103, 5)))
                .containsExactly(new Trade(2, 4, BUY, 101, 5));
    }

    @Test
    void earlierOrderWinsAtSamePrice() {
        book.submit(limit(1, BUY, 100, 5));
        book.submit(limit(2, BUY, 100, 5));
        book.submit(limit(3, BUY, 100, 5));

        assertThat(book.submit(limit(4, SELL, 100, 7)))
                .containsExactly(new Trade(1, 4, SELL, 100, 5), new Trade(2, 4, SELL, 100, 2));
        assertThat(book.depth(BUY, 1)).containsExactly(new PriceLevel(100, 8, 2));
    }

    @Test
    void sweepsSeveralLevelsUntilLimitStopsIt() {
        book.submit(limit(1, SELL, 100, 2));
        book.submit(limit(2, SELL, 101, 2));
        book.submit(limit(3, SELL, 102, 2));

        assertThat(book.submit(limit(4, BUY, 101, 10))).containsExactly(
                new Trade(1, 4, BUY, 100, 2),
                new Trade(2, 4, BUY, 101, 2),
                new OrderRested(4, BUY, 101, 6));
        assertThat(book.bestAsk()).hasValue(102);
        assertThat(book.bestBid()).hasValue(101);
    }

    @Test
    void remembersLastAcceptedId() {
        assertThat(book.lastOrderId()).isZero();
        book.submit(limit(7, BUY, 100, 1));

        assertThat(book.lastOrderId()).isEqualTo(7);
    }

    @Test
    void rejectsOrderIdThatDoesNotIncrease() {
        book.submit(limit(5, BUY, 100, 1));

        assertThatIllegalArgumentException().isThrownBy(() -> book.submit(limit(5, BUY, 100, 1)));
        assertThatIllegalArgumentException().isThrownBy(() -> book.submit(limit(4, BUY, 100, 1)));
        assertThat(book.depth(BUY, 10)).containsExactly(new PriceLevel(100, 1, 1));
    }
}
