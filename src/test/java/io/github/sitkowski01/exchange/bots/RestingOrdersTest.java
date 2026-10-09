package io.github.sitkowski01.exchange.bots;

import io.github.sitkowski01.exchange.domain.BookEvent;
import io.github.sitkowski01.exchange.domain.OrderType;
import io.github.sitkowski01.exchange.domain.Side;
import io.github.sitkowski01.exchange.engine.EventSink;
import io.github.sitkowski01.exchange.engine.MatchingEngine;
import io.github.sitkowski01.exchange.engine.OrderResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(10)
class RestingOrdersTest {

    private final MatchingEngine engine = new MatchingEngine(List.of("CDR"), 100, Clock.systemUTC(), EventSink.NONE);
    private final RestingOrders orders = new RestingOrders(engine.instrument("CDR"), Duration.ofMillis(100));

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    void remembersOnlyOrdersThatRest() throws Exception {
        orders.track(CompletableFuture.completedFuture(rested(7)));
        orders.track(CompletableFuture.completedFuture(new OrderResult(8,
                List.of(new BookEvent.Trade(7, 8, Side.SELL, 100, 1)))));

        assertThat(orders.ids()).containsExactly(7L);
    }

    /** Scenariusz z review: odpowiedz po czasie -- zlecenie i tak czeka w arkuszu, nie wolno o nim zapomniec. */
    @Test
    void lateAnswerIsPickedUpLater() throws Exception {
        CompletableFuture<OrderResult> slow = new CompletableFuture<>();
        assertThatThrownBy(() -> orders.track(slow)).isInstanceOf(TimeoutException.class);
        assertThat(orders.size()).isZero();

        slow.complete(rested(1));
        orders.track(CompletableFuture.completedFuture(rested(2)));

        assertThat(orders.ids()).containsExactlyInAnyOrder(1L, 2L);

        // Spozniona odpowiedz liczy sie raz -- kolejne zlecenia nie moga jej dopisywac ponownie.
        orders.track(CompletableFuture.completedFuture(rested(3)));
        assertThat(orders.ids()).containsExactly(1L, 2L, 3L);
    }

    @Test
    void lateFailureIsForgotten() throws Exception {
        CompletableFuture<OrderResult> slow = new CompletableFuture<>();
        assertThatThrownBy(() -> orders.track(slow)).isInstanceOf(TimeoutException.class);
        slow.completeExceptionally(new IllegalStateException("queue full"));

        orders.track(CompletableFuture.completedFuture(rested(2)));

        assertThat(orders.ids()).containsExactly(2L);
    }

    @Test
    void cancelOldestRemovesFromBookAndList() throws Exception {
        orders.track(engine.instrument("CDR").place(Side.BUY, OrderType.LIMIT, 100, 1));
        orders.track(engine.instrument("CDR").place(Side.BUY, OrderType.LIMIT, 101, 1));

        orders.cancelOldest();

        assertThat(orders.ids()).containsExactly(2L);
        assertThat(engine.instrument("CDR").snapshot(10).join().bids()).hasSize(1);
    }

    /** Anulowanie odrzucone (np. pelna kolejka) -- id zostaje, sprobujemy znowu. */
    @Test
    void rejectedCancelKeepsTheId() throws Exception {
        orders.track(CompletableFuture.completedFuture(rested(5)));
        engine.close();

        assertThatThrownBy(orders::cancelOldest).hasRootCauseMessage("CDR: engine closed");
        assertThat(orders.ids()).containsExactly(5L);
    }

    @Test
    void cancelOnEmptyDoesNothing() throws Exception {
        orders.cancelOldest();
        assertThat(orders.size()).isZero();
    }

    private static OrderResult rested(long id) {
        return new OrderResult(id, List.of(new BookEvent.OrderRested(id, Side.BUY, 100, 1)));
    }
}
