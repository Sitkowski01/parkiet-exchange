package io.github.sitkowski01.exchange.api;

/**
 * Silnik nie odpowiedzial na czas. W odroznieniu od pelnej kolejki stan jest nieznany:
 * polecenie moze nadal czekac w kolejce i wykonac sie pozniej.
 */
class EngineTimeoutException extends RuntimeException {

    EngineTimeoutException(String message) {
        super(message);
    }
}
