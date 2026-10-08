package io.github.sitkowski01.exchange.engine;

/** Silnik nie przyjal polecenia: kolejka pelna, silnik zamkniety albo nieznany instrument. */
public class EngineRejectedException extends RuntimeException {

    public EngineRejectedException(String message) {
        super(message);
    }
}
