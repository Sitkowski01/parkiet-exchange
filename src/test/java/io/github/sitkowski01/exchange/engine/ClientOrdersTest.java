package io.github.sitkowski01.exchange.engine;

import io.github.sitkowski01.exchange.domain.BookEvent;
import io.github.sitkowski01.exchange.domain.BookEvent.OrderCancelled;
import io.github.sitkowski01.exchange.domain.BookEvent.OrderRested;
import io.github.sitkowski01.exchange.domain.BookEvent.Trade;
import io.github.sitkowski01.exchange.domain.OrderType;
import io.github.sitkowski01.exchange.domain.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientOrdersTest {

    private static final ClientOrderId A = new ClientOrderId("broker-1");
    private static final ClientOrderId B = new ClientOrderId("broker-2");
    private static final ClientOrderId C = new ClientOrderId("broker-3");
    private static final ClientOrders.Spec SPEC = new ClientOrders.Spec(Side.BUY, OrderType.LIMIT, 100, 1);

    @Test
    void remembersResultsById() {
        ClientOrders orders = new ClientOrders(10);
        OrderResult result = new OrderResult(7, List.of(new OrderRested(7, Side.BUY, 100, 1)));

        orders.remember(A, SPEC, result);

        assertThat(orders.repeatOf(A, SPEC)).contains(result.asRepeat());
        assertThat(orders.repeatOf(B, SPEC)).isEmpty();
        assertThat(orders.size()).isEqualTo(1);
    }

    @Test
    void forgetsTheOldestOrderWhenTheWindowIsFull() {
        ClientOrders orders = new ClientOrders(2);
        orders.remember(A, SPEC, result(1));
        orders.remember(B, SPEC, result(2));
        // Powtorka nie odswieza wpisu: okno to ostatnio ZLOZONE zlecenia.
        assertThat(orders.repeatOf(A, SPEC)).isPresent();

        orders.remember(C, SPEC, result(3));

        assertThat(orders.repeatOf(A, SPEC)).isEmpty();
        assertThat(orders.repeatOf(B, SPEC)).isPresent();
        assertThat(orders.repeatOf(C, SPEC)).isPresent();
        assertThat(orders.size()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void windowMustBePositive(int capacity) {
        assertThatIllegalArgumentException().isThrownBy(() -> new ClientOrders(capacity))
                .withMessage("capacity must be positive, was " + capacity);
    }

    @Test
    void defaultWindowIsAHundredThousandOrders() {
        ClientOrders orders = ClientOrders.empty();
        for (int i = 1; i <= ClientOrders.DEFAULT_CAPACITY + 1; i++) {
            orders.remember(new ClientOrderId("o" + i), SPEC, result(i));
        }

        assertThat(orders.size()).isEqualTo(100_000);
        assertThat(orders.repeatOf(new ClientOrderId("o1"), SPEC)).isEmpty();
    }

    @Test
    void rebuildsEachOrderFromItsEventsInTheJournal() {
        // A: dwie transakcje i reszta w arkuszu; B: PKC bez plynnosci; C: od razu w arkuszu.
        List<EngineEvent> journal = List.of(
                tagged(5, new Trade(1, 3, Side.BUY, 100, 2), A),
                tagged(6, new Trade(2, 3, Side.BUY, 101, 1), A),
                tagged(7, new OrderRested(3, Side.BUY, 101, 4), A),
                tagged(8, new OrderCancelled(4, 9, OrderCancelled.Reason.NO_LIQUIDITY), B),
                tagged(9, new OrderRested(5, Side.SELL, 120, 1), C));

        ClientOrders orders = ClientOrders.rebuild(10, journal);

        assertThat(orders.repeatOf(A, SPEC)).contains(new OrderResult(3, List.of(
                new Trade(1, 3, Side.BUY, 100, 2), new Trade(2, 3, Side.BUY, 101, 1), new OrderRested(3, Side.BUY, 101, 4)),
                true));
        assertThat(orders.repeatOf(B, SPEC)).contains(new OrderResult(4,
                List.of(new OrderCancelled(4, 9, OrderCancelled.Reason.NO_LIQUIDITY)), true));
        assertThat(orders.repeatOf(C, SPEC).orElseThrow().orderId()).isEqualTo(5);
        assertThat(orders.size()).isEqualTo(3);
    }

    @Test
    void rebuildKeepsOnlyTheWindow() {
        ClientOrders orders = ClientOrders.rebuild(1, List.of(
                tagged(1, new OrderRested(1, Side.BUY, 100, 1), A),
                tagged(2, new OrderRested(2, Side.BUY, 100, 1), B)));

        assertThat(orders.repeatOf(A, SPEC)).isEmpty();
        assertThat(orders.repeatOf(B, SPEC)).isPresent();
    }

    @Test
    void rebuildOfNothingIsEmpty() {
        assertThat(ClientOrders.rebuild(10, List.of()).size()).isZero();
    }

    @ParameterizedTest
    @NullSource
    void rebuildRefusesEventsWithoutAnId(ClientOrderId missing) {
        List<EngineEvent> journal = List.of(tagged(4, new OrderRested(1, Side.BUY, 100, 1), missing));

        assertThatIllegalArgumentException().isThrownBy(() -> ClientOrders.rebuild(10, journal))
                .withMessage("event 4 has no client order id");
    }

    @Test
    void repeatIsTheSameResultMarkedAsRepeated() {
        OrderResult first = result(9);

        OrderResult repeat = first.asRepeat();

        assertThat(first.repeated()).isFalse();
        assertThat(repeat.repeated()).isTrue();
        assertThat(repeat.orderId()).isEqualTo(9);
        assertThat(repeat.events()).isEqualTo(first.events());
    }

    private static OrderResult result(long orderId) {
        return new OrderResult(orderId, List.of(new OrderRested(orderId, Side.BUY, 100, 1)));
    }

    private static EngineEvent tagged(long sequence, BookEvent event, ClientOrderId id) {
        return new EngineEvent("CDR", sequence, Instant.EPOCH, event, id);
    }

    @Test
    void sameIdWithDifferentParametersIsAConflict() {
        ClientOrders orders = new ClientOrders(10);
        orders.remember(A, SPEC, result(1));

        assertThatThrownBy(() -> orders.repeatOf(A, new ClientOrders.Spec(Side.SELL, OrderType.LIMIT, 100, 1)))
                .isInstanceOf(ClientOrderIdConflictException.class)
                .hasMessage("client order id 'broker-1' was already used for a different order");
        assertThatThrownBy(() -> orders.repeatOf(A, new ClientOrders.Spec(Side.BUY, OrderType.LIMIT, 100, 2)))
                .isInstanceOf(ClientOrderIdConflictException.class);
    }

    @Test
    void afterRebuildOnlyTheIdIsChecked() {
        // Dziennik nie zna parametrow zlecenia, wiec po restarcie nie ma z czym ich porownac.
        ClientOrders orders = ClientOrders.rebuild(10, List.of(tagged(1, new OrderRested(1, Side.BUY, 100, 1), A)));

        assertThat(orders.repeatOf(A, new ClientOrders.Spec(Side.SELL, OrderType.MARKET, 0, 7))).isPresent();
    }

    @Test
    void idReusedAfterLeavingTheWindowCountsAsNew() {
        ClientOrders orders = new ClientOrders(2);
        orders.remember(A, SPEC, result(1));
        orders.remember(B, SPEC, result(2));
        orders.remember(A, SPEC, result(3));

        orders.remember(C, SPEC, result(4));

        // A trafil na koniec okna przy ponownym uzyciu, wiec wypadl B, nie A.
        assertThat(orders.repeatOf(A, SPEC).orElseThrow().orderId()).isEqualTo(3);
        assertThat(orders.repeatOf(B, SPEC)).isEmpty();
    }

    @Test
    void rebuildKeepsTheLatestUseOfAReusedId() {
        ClientOrders orders = ClientOrders.rebuild(2, List.of(
                tagged(1, new OrderRested(1, Side.BUY, 100, 1), A),
                tagged(2, new OrderRested(2, Side.BUY, 100, 1), B),
                tagged(3, new OrderRested(3, Side.BUY, 100, 1), A),
                tagged(4, new OrderRested(4, Side.BUY, 100, 1), C)));

        assertThat(orders.repeatOf(A, SPEC).orElseThrow().orderId()).isEqualTo(3);
        assertThat(orders.repeatOf(B, SPEC)).isEmpty();
    }
}
