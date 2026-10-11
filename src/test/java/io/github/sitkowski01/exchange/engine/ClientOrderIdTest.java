package io.github.sitkowski01.exchange.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ClientOrderIdTest {

    @ParameterizedTest
    @ValueSource(strings = {"a", "broker-7f3a", "A.b_c:d-9", "0123456789012345678901234567890123456789012345678901234567890123"})
    void acceptsLettersDigitsAndSeparators(String value) {
        assertThat(new ClientOrderId(value).value()).isEqualTo(value);
        assertThat(new ClientOrderId(value)).hasToString(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "has space", "zlecenie-ą", "a/b", "01234567890123456789012345678901234567890123456789012345678901234"})
    void rejectsEverythingElse(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> new ClientOrderId(value))
                .withMessage("client order id must match [A-Za-z0-9._:-]{1,64}, was '" + value + "'");
    }

    @Test
    void nullMeansNoIdInBothDirections() {
        assertThat(ClientOrderId.ofNullable(null)).isNull();
        assertThat(ClientOrderId.ofNullable("x")).isEqualTo(new ClientOrderId("x"));
        assertThat(ClientOrderId.valueOrNull(null)).isNull();
        assertThat(ClientOrderId.valueOrNull(new ClientOrderId("x"))).isEqualTo("x");
        assertThatNullPointerException().isThrownBy(() -> new ClientOrderId(null));
    }
}
