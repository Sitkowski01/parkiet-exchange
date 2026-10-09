package io.github.sitkowski01.exchange.engine;

import io.github.sitkowski01.exchange.engine.SinkFixtures.Recorder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.sitkowski01.exchange.engine.SinkFixtures.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventSinkFanOutTest {

    private final Recorder first = new Recorder();
    private final Recorder second = new Recorder();

    @Test
    void everySinkGetsTheBatch() {
        EventSink.fanOut(List.of(first, second)).publish(List.of(event(1), event(2)));

        assertThat(first.calls).containsExactly(List.of(1L, 2L));
        assertThat(second.calls).containsExactly(List.of(1L, 2L));
    }

    @Test
    void failingSinkDoesNotStarveTheOthers() {
        IllegalStateException boom = new IllegalStateException("market data down");
        IllegalStateException bang = new IllegalStateException("second failure");
        EventSink sink = EventSink.fanOut(List.of(batch -> {
            throw boom;
        }, first, batch -> {
            throw bang;
        }, second));

        assertThatThrownBy(() -> sink.publish(List.of(event(1)))).isSameAs(boom).hasSuppressedException(bang);
        assertThat(first.calls).containsExactly(List.of(1L));
        assertThat(second.calls).containsExactly(List.of(1L));
    }

    @Test
    void noSinksMeansNone() {
        assertThat(EventSink.fanOut(List.of())).isSameAs(EventSink.NONE);
    }

    @Test
    void singleSinkIsUsedDirectly() {
        assertThat(EventSink.fanOut(List.of(first))).isSameAs(first);
    }
}
