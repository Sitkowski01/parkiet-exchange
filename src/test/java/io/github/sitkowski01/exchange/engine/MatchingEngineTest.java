package io.github.sitkowski01.exchange.engine;

import io.github.sitkowski01.exchange.domain.BookEvent.OrderRested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;

import static io.github.sitkowski01.exchange.domain.OrderType.LIMIT;
import static io.github.sitkowski01.exchange.domain.Side.BUY;
import static io.github.sitkowski01.exchange.domain.Side.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatchingEngineTest {

    @Test
    void instrumentsAreIndependent() {
        try (MatchingEngine engine = new MatchingEngine(List.of("CDR", "PKO"), 16, Clock.systemUTC(), EventSink.NONE)) {
            engine.instrument("CDR").place(SELL, LIMIT, 100, 5).join();

            OrderResult pko = engine.instrument("PKO").place(BUY, LIMIT, 100, 5).join();

            assertThat(pko).isEqualTo(new OrderResult(1, List.of(new OrderRested(1, BUY, 100, 5))));
            assertThat(engine.symbols()).containsExactly("CDR", "PKO");
        }
    }

    @Test
    void unknownSymbolIsReported() {
        try (MatchingEngine engine = new MatchingEngine(List.of("CDR"), 16, Clock.systemUTC(), EventSink.NONE)) {
            assertThatThrownBy(() -> engine.instrument("XYZ"))
                    .isInstanceOf(UnknownInstrumentException.class)
                    .hasMessageContaining("XYZ");
        }
    }

    @Test
    void rejectsInvalidConfigurationBeforeStartingThreads() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MatchingEngine(List.of("CDR", "CDR"), 16, Clock.systemUTC(), EventSink.NONE));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MatchingEngine(List.of("CDR"), 0, Clock.systemUTC(), EventSink.NONE))
                .withMessageContaining("queue capacity");
    }

    @Test
    void closesAlreadyStartedInstrumentsWhenStartFailsHalfway() {
        assertThatThrownBy(() -> new MatchingEngine(Arrays.asList("LEAK", null), 16, Clock.systemUTC(), EventSink.NONE))
                .isInstanceOf(NullPointerException.class);

        assertThat(Thread.getAllStackTraces().keySet())
                .noneMatch(t -> t.getName().equals("engine-LEAK") && t.isAlive());
    }

    @Test
    void closeStopsAllInstruments() {
        MatchingEngine engine = new MatchingEngine(List.of("CDR", "PKO"), 16, Clock.systemUTC(), EventSink.NONE);
        engine.close();

        for (String symbol : List.of("CDR", "PKO")) {
            assertThatThrownBy(() -> engine.instrument(symbol).snapshot(1).join())
                    .hasCauseInstanceOf(EngineRejectedException.class);
        }
    }
}
