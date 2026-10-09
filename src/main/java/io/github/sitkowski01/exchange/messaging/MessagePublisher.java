package io.github.sitkowski01.exchange.messaging;

import java.util.concurrent.CompletableFuture;

/**
 * Wyslanie jednej wiadomosci do brokera. W produkcji Kafka, w testach relaya -- atrapa,
 * ktora pamieta wiadomosci albo udaje awarie.
 */
@FunctionalInterface
public interface MessagePublisher {

    /** @return konczy sie, gdy broker potwierdzi zapis, albo bledem */
    CompletableFuture<?> send(String key, String value);
}
