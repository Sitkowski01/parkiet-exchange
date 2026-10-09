package io.github.sitkowski01.exchange.api;

/**
 * Bledne zlecenie wykryte na brzegu → 400.
 *
 * <p>Osobny typ zamiast {@link IllegalArgumentException}: IAE rzucone gleboko w domenie
 * albo w bibliotece to blad serwera (500), a nie klienta, i nie moze wyjsc jako 400.
 */
class InvalidOrderException extends RuntimeException {

    InvalidOrderException(String message) {
        super(message);
    }

    InvalidOrderException(String message, Throwable cause) {
        super(message, cause);
    }
}
