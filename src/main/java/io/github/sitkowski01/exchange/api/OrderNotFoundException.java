package io.github.sitkowski01.exchange.api;

/** Zlecenia nie ma w arkuszu: nigdy nie istnialo, juz sie zrealizowalo albo zostalo anulowane. */
class OrderNotFoundException extends RuntimeException {

    OrderNotFoundException(String symbol, long orderId) {
        super("order " + orderId + " is not resting in the " + symbol + " book");
    }

    /** Zlecenie z poprzedniego przebiegu: arkusze zaczely od zera, a ten numer moze miec teraz cudze zlecenie. */
    OrderNotFoundException(String symbol, long orderId, long orderRun, long currentRun) {
        super("order " + orderId + " of run " + orderRun + " is not in the " + symbol + " book: the exchange is on run "
                + currentRun);
    }
}
