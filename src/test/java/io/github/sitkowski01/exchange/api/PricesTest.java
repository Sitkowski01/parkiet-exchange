package io.github.sitkowski01.exchange.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricesTest {

    @ParameterizedTest
    @CsvSource({
            "101.25, 10125",
            "101.2, 10120",
            "101, 10100",
            "0.01, 1",
            // Zera na koncu nie sa ulamkiem grosza.
            "101.2500, 10125",
            "92233720368547758.07, 9223372036854775807"
    })
    void zlotyToGrosze(String zloty, long grosze) {
        assertThat(Prices.toGrosze(new BigDecimal(zloty))).isEqualTo(grosze);
    }

    @ParameterizedTest
    @ValueSource(strings = {"101.255", "0.001", "1E-3"})
    void fractionOfGroszIsRejected(String zloty) {
        assertThatThrownBy(() -> Prices.toGrosze(new BigDecimal(zloty)))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("at most 2 decimal places");
    }

    @Test
    void priceBeyondLongIsRejected() {
        assertThatThrownBy(() -> Prices.toGrosze(new BigDecimal("92233720368547758.08")))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    void groszeToZlotyKeepsTwoDecimals() {
        assertThat(Prices.toZloty(10120)).hasToString("101.20");
        assertThat(Prices.toZloty(1)).hasToString("0.01");
    }
}
