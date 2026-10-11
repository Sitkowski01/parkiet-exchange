package io.github.sitkowski01.exchange.domain;

import io.github.sitkowski01.exchange.domain.BookEvent.OrderCancelled;
import io.github.sitkowski01.exchange.domain.BookEvent.OrderRested;
import io.github.sitkowski01.exchange.domain.BookEvent.Trade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Event sourcing: arkusz odbudowany z dziennika musi byc nieodroznialny od oryginalu --
 * nie tylko wygladac tak samo (glebokosc), ale tez tak samo reagowac na kolejne zlecenia
 * (to sprawdza priorytet czasu, ktorego w glebokosci nie widac).
 */
class OrderBookRebuildTest {

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 7, 42, 2026, 31337})
    void rebuiltBookIsIndistinguishableFromOriginal(long seed) {
        Random random = new Random(seed);
        OrderBook original = new OrderBook();
        List<BookEvent> journal = new ArrayList<>();
        long id = 0;
        for (int step = 0; step < 5_000; step++) {
            id = act(random, original, id, journal);
        }

        OrderBook rebuilt = OrderBook.rebuild(journal);

        assertSameState(original, rebuilt);
        // Ten sam ciag dalszych zlecen na obu arkuszach -> identyczne zdarzenia.
        Random next = new Random(seed * 31);
        List<BookEvent> fromOriginal = new ArrayList<>();
        List<BookEvent> fromRebuilt = new ArrayList<>();
        long idA = id;
        long idB = id;
        for (int step = 0; step < 2_000; step++) {
            long before = next.nextLong();
            idA = act(new Random(before), original, idA, fromOriginal);
            idB = act(new Random(before), rebuilt, idB, fromRebuilt);
        }
        assertThat(fromRebuilt).isEqualTo(fromOriginal);
        assertSameState(original, rebuilt);
    }

    /** Start z migawki: migawka z losowego miejsca + zdarzenia po niej = to samo co caly dziennik. */
    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {3, 11, 99, 4242})
    void snapshotPlusTailIsIndistinguishableFromOriginal(long seed) {
        Random random = new Random(seed);
        OrderBook original = new OrderBook();
        List<BookEvent> journal = new ArrayList<>();
        int snapshotAt = 1_000 + random.nextInt(3_000);
        OrderBook.Snapshot snapshot = null;
        int tailFrom = 0;
        long id = 0;
        for (int step = 0; step < 5_000; step++) {
            if (step == snapshotAt) {
                snapshot = original.snapshot();
                tailFrom = journal.size();
            }
            id = act(random, original, id, journal);
        }

        OrderBook.Rebuilder rebuilder = OrderBook.Rebuilder.from(snapshot);
        journal.subList(tailFrom, journal.size()).forEach(rebuilder::apply);
        OrderBook rebuilt = rebuilder.finish();

        assertSameState(original, rebuilt);
        Random next = new Random(seed * 17);
        List<BookEvent> fromOriginal = new ArrayList<>();
        List<BookEvent> fromRebuilt = new ArrayList<>();
        long idA = id;
        long idB = id;
        for (int step = 0; step < 2_000; step++) {
            long before = next.nextLong();
            idA = act(new Random(before), original, idA, fromOriginal);
            idB = act(new Random(before), rebuilt, idB, fromRebuilt);
        }
        assertThat(fromRebuilt).isEqualTo(fromOriginal);
    }

    @Test
    void snapshotKeepsTimePriorityAndOrdersThatNeverRested() {
        OrderBook book = new OrderBook();
        book.submit(OrderRequest.limit(1, Side.SELL, 100, 5));
        book.submit(OrderRequest.limit(2, Side.SELL, 100, 5));
        book.submit(OrderRequest.limit(3, Side.BUY, 90, 1));
        book.submit(OrderRequest.market(4, Side.SELL, 1));

        OrderBook.Snapshot snapshot = book.snapshot();
        OrderBook rebuilt = OrderBook.Rebuilder.from(snapshot).finish();

        assertThat(snapshot.orders()).containsExactly(
                new OrderRested(1, Side.SELL, 100, 5), new OrderRested(2, Side.SELL, 100, 5));
        assertThat(rebuilt.lastOrderId()).isEqualTo(4);
        // Starsze zlecenie po tej samej cenie dalej idzie pierwsze.
        assertThat(rebuilt.submit(OrderRequest.market(5, Side.BUY, 3)))
                .containsExactly(new Trade(1, 5, Side.BUY, 100, 3));
    }

    @Test
    void reservedOrderIdsAreNeverHandedOutAgain() {
        OrderBook.Rebuilder rebuilder = new OrderBook.Rebuilder();
        rebuilder.apply(new OrderRested(7, Side.BUY, 100, 2));
        rebuilder.reserveOrderIdsUpTo(1_007);
        rebuilder.reserveOrderIdsUpTo(3);
        assertThat(rebuilder.lastOrderId()).as("rezerwacja nigdy nie cofa numeracji").isEqualTo(1_007);
        OrderBook book = rebuilder.finish();

        assertThat(book.lastOrderId()).isEqualTo(1_007);
        assertThatThrownBy(() -> book.submit(OrderRequest.limit(500, Side.BUY, 100, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderBook.Rebuilder().reserveOrderIdsUpTo(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderBook.Snapshot(List.of(), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyJournalGivesEmptyBook() {
        OrderBook book = OrderBook.rebuild(List.of());

        assertThat(book.lastOrderId()).isZero();
        assertThat(book.bestBid()).isEmpty();
    }

    @Test
    void lastOrderIdCountsOrdersThatNeverRested() {
        OrderBook book = OrderBook.rebuild(List.of(
                new OrderRested(1, Side.SELL, 100, 5),
                new Trade(1, 2, Side.BUY, 100, 5),
                new OrderCancelled(3, 4, OrderCancelled.Reason.NO_LIQUIDITY)));

        assertThat(book.lastOrderId()).isEqualTo(3);
        assertThat(book.bestAsk()).isEmpty();
    }

    @Test
    void partialFillKeepsTheRest() {
        OrderBook book = OrderBook.rebuild(List.of(
                new OrderRested(1, Side.SELL, 100, 10),
                new OrderRested(2, Side.SELL, 100, 3),
                new Trade(1, 3, Side.BUY, 100, 4)));

        assertThat(book.depth(Side.SELL, 5)).containsExactly(new PriceLevel(100, 9, 2));
    }

    @Test
    void inconsistentJournalIsRejected() {
        assertThatThrownBy(() -> OrderBook.rebuild(List.of(new Trade(9, 10, Side.BUY, 100, 1))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("does not match");
        assertThatThrownBy(() -> OrderBook.rebuild(List.of(new OrderRested(1, Side.BUY, 100, 2),
                new Trade(1, 2, Side.SELL, 100, 3))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("does not match");
        assertThatThrownBy(() -> OrderBook.rebuild(List.of(
                new OrderCancelled(5, 1, OrderCancelled.Reason.REQUESTED))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not resting");
        assertThatThrownBy(() -> OrderBook.rebuild(List.of(new OrderRested(1, Side.BUY, 100, 2),
                new OrderRested(1, Side.BUY, 100, 2))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("rested twice");
    }

    @Test
    void finishedRebuilderAcceptsNothingMore() {
        OrderBook.Rebuilder rebuilder = new OrderBook.Rebuilder();
        rebuilder.apply(new OrderRested(1, Side.BUY, 100, 2));
        OrderBook book = rebuilder.finish();

        // Arkusz nalezy juz do silnika: dalsze zdarzenia zmienialyby go za jego plecami.
        assertThatThrownBy(() -> rebuilder.apply(new OrderRested(2, Side.BUY, 100, 2)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("finished");
        assertThatThrownBy(rebuilder::finish).isInstanceOf(IllegalStateException.class);
        assertThat(book.depth(Side.BUY, 5)).containsExactly(new PriceLevel(100, 2, 1));
    }

    /** Losowe zlecenie albo anulowanie; waski zakres cen, zeby duzo sie krzyzowalo. */
    private static long act(Random random, OrderBook book, long lastId, List<BookEvent> journal) {
        if (lastId > 0 && random.nextInt(10) < 2) {
            book.cancel(1 + random.nextLong(lastId)).ifPresent(journal::add);
            return lastId;
        }
        long id = lastId + 1;
        Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
        long quantity = 1 + random.nextInt(20);
        OrderRequest order = random.nextInt(10) == 0
                ? OrderRequest.market(id, side, quantity)
                : OrderRequest.limit(id, side, 95 + random.nextInt(11), quantity);
        journal.addAll(book.submit(order));
        return id;
    }

    private static void assertSameState(OrderBook expected, OrderBook actual) {
        assertThat(actual.lastOrderId()).isEqualTo(expected.lastOrderId());
        assertThat(actual.depth(Side.BUY, 1000)).isEqualTo(expected.depth(Side.BUY, 1000));
        assertThat(actual.depth(Side.SELL, 1000)).isEqualTo(expected.depth(Side.SELL, 1000));
    }
}
