package io.github.sitkowski01.exchange.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** Przebieg pochodzi z identity w bazie, wiec zaczyna sie od 1 — zero znaczyloby, ze cos poszlo nie tak. */
class ExchangeRunTest {

    @Test
    void runIdsStartAtOne() {
        assertThat(new ExchangeRun(1).id()).isEqualTo(1);
        assertThatIllegalArgumentException().isThrownBy(() -> new ExchangeRun(0)).withMessage("run id must be positive: 0");
    }
}
