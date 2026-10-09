package io.github.sitkowski01.exchange.api;

/** Zlecenia nie ma w arkuszu: nigdy nie istnialo, juz sie zrealizowalo albo zostalo anulowane. */
class OrderNotFoundException extends RuntimeException {

    OrderNotFoundException(String symbol, long orderId) {
        super("order " + orderId + " is not resting in the " + symbol + " book");
    }
}
