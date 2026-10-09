package io.github.sitkowski01.exchange.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineCallsTest {

    private static final Duration TIMEOUT = Duration.ofMillis(50);

    @Test
    void returnsValue() {
        assertThat(EngineCalls.await(CompletableFuture.completedFuture(7), TIMEOUT)).isEqualTo(7);
    }

    @Test
    void rethrowsOriginalRuntimeException() {
        IllegalArgumentException cause = new IllegalArgumentException("bad");
        assertThatThrownBy(() -> EngineCalls.await(CompletableFuture.failedFuture(cause), TIMEOUT))
                .isSameAs(cause);
    }

    @Test
    void rethrowsOriginalError() {
        AssertionError cause = new AssertionError("boom");
        assertThatThrownBy(() -> EngineCalls.await(CompletableFuture.failedFuture(cause), TIMEOUT))
                .isSameAs(cause);
    }

    @Test
    void wrapsCheckedException() {
        IOException cause = new IOException("io");
        assertThatThrownBy(() -> EngineCalls.await(CompletableFuture.failedFuture(cause), TIMEOUT))
                .isInstanceOf(IllegalStateException.class)
                .hasCause(cause);
    }

    @Test
    void timesOut() {
        assertThatThrownBy(() -> EngineCalls.await(new CompletableFuture<>(), TIMEOUT))
                .isInstanceOf(EngineTimeoutException.class)
                .hasMessageContaining("PT0.05S");
    }

    @Test
    void interruptKeepsFlagAndReportsUnknownState() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> EngineCalls.await(new CompletableFuture<>(), Duration.ofSeconds(10)))
                    .isInstanceOf(EngineTimeoutException.class)
                    .hasMessageContaining("interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
