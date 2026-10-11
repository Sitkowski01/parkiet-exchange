package io.github.sitkowski01.exchange.engine;

/** Znany {@link ClientOrderId} przyszedl z innymi parametrami zlecenia -- nie powtorka, tylko pomylka klienta. */
public final class ClientOrderIdConflictException extends RuntimeException {

    public ClientOrderIdConflictException(ClientOrderId id) {
        super("client order id '" + id + "' was already used for a different order");
    }
}
