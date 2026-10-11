package io.github.sitkowski01.exchange.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class OrderRequestTest {

    @ParameterizedTest
    @ValueSource(longs = {0, -1, OrderRequest.MAX_QUANTITY + 1})
    void rejectsQuantityOutsideRange(long quantity) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> OrderRequest.limit(1, Side.BUY, 100, quantity))
                .withMessageContaining("quantity");
    }

    @Test
    void acceptsMaximumQuantity() {
        assertThat(OrderRequest.limit(1, Side.BUY, 100, OrderRequest.MAX_QUANTITY).quantity())
                .isEqualTo(OrderRequest.MAX_QUANTITY);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositiveId(long id) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> OrderRequest.limit(id, Side.BUY, 100, 10))
                .withMessageContaining("id");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositiveLimitPrice(long price) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> OrderRequest.limit(1, Side.BUY, price, 10))
                .withMessageContaining("price");
    }

    @Test
    void priceIsCappedSoTurnoverFitsInLong() {
        assertThat(OrderRequest.limit(1, Side.BUY, OrderRequest.MAX_PRICE, 10).price()).isEqualTo(OrderRequest.MAX_PRICE);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> OrderRequest.limit(1, Side.BUY, OrderRequest.MAX_PRICE + 1, 10))
                .withMessageContaining("price");
        assertThat(Math.multiplyHigh(OrderRequest.MAX_PRICE, OrderRequest.MAX_QUANTITY)).isZero();
    }

    @Test
    void rejectsMarketOrderWithPrice() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OrderRequest(1, Side.BUY, OrderType.MARKET, 100, 10));
    }

    @Test
    void rejectsMissingSideOrType() {
        assertThatNullPointerException().isThrownBy(() -> new OrderRequest(1, null, OrderType.LIMIT, 100, 10));
        assertThatNullPointerException().isThrownBy(() -> new OrderRequest(1, Side.BUY, null, 100, 10));
    }

    @Test
    void factoriesSetTypeAndPrice() {
        assertThat(OrderRequest.limit(1, Side.SELL, 250, 3))
                .isEqualTo(new OrderRequest(1, Side.SELL, OrderType.LIMIT, 250, 3));
        assertThat(OrderRequest.market(2, Side.BUY, 5))
                .isEqualTo(new OrderRequest(2, Side.BUY, OrderType.MARKET, 0, 5));
    }
}
