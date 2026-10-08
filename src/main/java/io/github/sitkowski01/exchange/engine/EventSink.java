package io.github.sitkowski01.exchange.engine;

import java.util.List;

/**
 * Odbiorca zdarzen silnika: pozniej outbox w bazie, Kafka, WebSocket.
 *
 * <p>Wywolywany z watku silnika, raz na polecenie, z cala paczka zdarzen tego polecenia.
 * Musi byc szybki -- dopoki nie wroci, instrument stoi.
 */
@FunctionalInterface
public interface EventSink {

    void publish(List<EngineEvent> batch);

    EventSink NONE = batch -> {
    };
}
