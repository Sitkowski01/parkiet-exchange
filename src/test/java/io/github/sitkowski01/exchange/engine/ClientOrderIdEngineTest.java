package io.github.sitkowski01.exchange.engine;

import io.github.sitkowski01.exchange.domain.BookEvent.OrderRested;
import io.github.sitkowski01.exchange.domain.BookEvent.Trade;
import io.github.sitkowski01.exchange.domain.OrderBook;
import io.github.sitkowski01.exchange.domain.OrderType;
import io.github.sitkowski01.exchange.domain.Side;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Powtorka zlecenia z tym samym identyfikatorem klienta: ten sam wynik, zero zmian w arkuszu. */
class ClientOrderIdEngineTest {

    private static final ClientOrderId ID = new ClientOrderId("broker-1");

    private final List<EngineEvent> published = Collections.synchronizedList(new ArrayList<>());
    private final InstrumentEngine cdr = InstrumentEngine.start("CDR", 1000,
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), published::addAll);

    @AfterEach
    void close() {
        cdr.close();
    }

    @Test
    void repeatReturnsTheOriginalResultAndChangesNothing() {
        cdr.place(Side.SELL, OrderType.LIMIT, 100, 3).join();
        OrderResult first = cdr.place(ID, Side.BUY, OrderType.LIMIT, 100, 5).join();
        int eventsAfterFirst = published.size();

        OrderResult repeat = cdr.place(ID, Side.BUY, OrderType.LIMIT, 100, 5).join();

        assertThat(first.repeated()).isFalse();
        assertThat(repeat.repeated()).isTrue();
        assertThat(repeat.orderId()).isEqualTo(first.orderId()).isEqualTo(2);
        assertThat(repeat.events()).isEqualTo(first.events())
                .containsExactly(new Trade(1, 2, Side.BUY, 100, 3), new OrderRested(2, Side.BUY, 100, 2));
        assertThat(published).hasSize(eventsAfterFirst);
        assertThat(cdr.snapshot(5).join().sequence()).isEqualTo(eventsAfterFirst);
    }

    @Test
    void repeatDoesNotUseUpAnOrderId() {
        cdr.place(ID, Side.BUY, OrderType.LIMIT, 100, 1).join();
        cdr.place(ID, Side.BUY, OrderType.LIMIT, 100, 1).join();

        assertThat(cdr.place(Side.BUY, OrderType.LIMIT, 100, 1).join().orderId()).isEqualTo(2);
    }

    @Test
    void sameIdForADifferentOrderIsRefusedAndChangesNothing() {
        cdr.place(ID, Side.BUY, OrderType.LIMIT, 100, 1).join();
        int events = published.size();

        assertThatThrownBy(() -> cdr.place(ID, Side.SELL, OrderType.LIMIT, 999, 50).join())
                .hasCauseInstanceOf(ClientOrderIdConflictException.class);

        assertThat(published).hasSize(events);
        assertThat(cdr.snapshot(5).join().asks()).isEmpty();
        assertThat(cdr.place(Side.BUY, OrderType.LIMIT, 100, 1).join().orderId()).isEqualTo(2);
    }

    @Test
    void differentIdsAreDifferentOrders() {
        long a = cdr.place(new ClientOrderId("a"), Side.BUY, OrderType.LIMIT, 100, 1).join().orderId();
        long b = cdr.place(new ClientOrderId("b"), Side.BUY, OrderType.LIMIT, 100, 1).join().orderId();

        assertThat(List.of(a, b)).containsExactly(1L, 2L);
    }

    @Test
    void eventsOfTheOrderCarryItsIdButCancellationsDoNot() {
        cdr.place(Side.SELL, OrderType.LIMIT, 100, 1).join();
        cdr.place(ID, Side.BUY, OrderType.LIMIT, 100, 2).join();
        cdr.cancel(2).join();

        assertThat(published).extracting(EngineEvent::clientOrderId).containsExactly(null, ID, ID, null);
    }

    @Test
    void parallelRepeatsPlaceTheOrderOnce() {
        List<CompletableFuture<OrderResult>> attempts = IntStream.range(0, 50)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> cdr.place(ID, Side.BUY, OrderType.LIMIT, 100, 1).join()))
                .toList();

        List<OrderResult> results = attempts.stream().map(CompletableFuture::join).toList();

        assertThat(results).filteredOn(r -> !r.repeated()).hasSize(1);
        assertThat(results).extracting(OrderResult::orderId).containsOnly(1L);
        assertThat(cdr.snapshot(5).join().bids()).singleElement().satisfies(l -> assertThat(l.quantity()).isEqualTo(1));
    }

    @Test
    void rejectedOrderIsNotRemembered() {
        assertThatThrownBy(() -> cdr.place(ID, Side.BUY, OrderType.LIMIT, 100, 0).join())
                .hasCauseInstanceOf(IllegalArgumentException.class);

        OrderResult corrected = cdr.place(ID, Side.BUY, OrderType.LIMIT, 100, 1).join();

        assertThat(corrected.repeated()).isFalse();
        assertThat(corrected.orderId()).isEqualTo(1);
    }

    @Test
    void stateCarriesTheMemoryAcrossRestart() {
        ClientOrders memory = new ClientOrders(10);
        OrderResult before = new OrderResult(41, List.of(new OrderRested(41, Side.BUY, 100, 1)));
        memory.remember(ID, new ClientOrders.Spec(Side.BUY, OrderType.LIMIT, 100, 1), before);
        OrderBook book = new OrderBook();

        try (InstrumentEngine restarted = InstrumentEngine.start("PKO", 10, Clock.systemUTC(), batch -> { },
                new InstrumentState(book, 7, memory))) {
            OrderResult repeat = restarted.place(ID, Side.BUY, OrderType.LIMIT, 100, 1).join();

            assertThat(repeat).isEqualTo(new OrderResult(41, before.events(), true));
        }
    }

    @Test
    void stateRequiresTheMemory() {
        assertThatThrownBy(() -> new InstrumentState(new OrderBook(), 0, null))
                .isInstanceOf(NullPointerException.class).hasMessage("clientOrders");
        assertThat(InstrumentState.empty().clientOrders().size()).isZero();
    }
}
