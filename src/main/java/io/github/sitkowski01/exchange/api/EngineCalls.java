package io.github.sitkowski01.exchange.api;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Czekanie na odpowiedz silnika z limitem czasu i rozpakowaniem bledu z watku silnika. */
final class EngineCalls {

    private EngineCalls() {
    }

    static <T> T await(CompletableFuture<T> future, Duration timeout) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            // Blad powstal na watku silnika; wyrzucamy oryginal, zeby obsluga bledow
            // widziala np. IllegalArgumentException, a nie opakowanie.
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } catch (TimeoutException e) {
            throw new EngineTimeoutException("engine did not answer within " + timeout);
        } catch (InterruptedException e) {
            // Dla klienta to to samo co limit czasu: polecenie juz jest w kolejce, odpowiedzi nie bedzie.
            Thread.currentThread().interrupt();
            throw new EngineTimeoutException("interrupted while waiting for the engine");
        }
    }
}
