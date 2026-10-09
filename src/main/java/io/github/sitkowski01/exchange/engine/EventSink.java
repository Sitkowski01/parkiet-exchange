package io.github.sitkowski01.exchange.engine;

import java.util.List;

/**
 * Odbiorca zdarzen silnika: dziennik w bazie, notowania dla klientow.
 *
 * <p>Wywolywany z watku silnika, raz na polecenie, z cala paczka zdarzen tego polecenia.
 * Musi byc szybki -- dopoki nie wroci, instrument stoi. Wolnego odbiorce owin w {@link AsyncEventSink}.
 */
@FunctionalInterface
public interface EventSink {

    void publish(List<EngineEvent> batch);

    EventSink NONE = batch -> {
    };

    /**
     * Jedna paczka do kilku odbiorcow, po kolei. Blad jednego nie zabiera paczki pozostalym:
     * padniete notowania nie moga zatrzymac zapisu do dziennika. Pierwszy blad leci dalej
     * (silnik go zaloguje), kolejne sa do niego dolaczone jako "suppressed".
     *
     * <p>Chroni tylko przed wyjatkami, nie przed czekaniem: odbiorca, ktory blokuje, wstrzymuje
     * nastepnych. Dlatego notowania nigdy nie czekaja (gubia paczke), a czekac wolno tylko
     * dziennikowi -- to celowy backpressure.
     */
    static EventSink fanOut(List<EventSink> sinks) {
        List<EventSink> copy = List.copyOf(sinks);
        if (copy.isEmpty()) {
            return NONE;
        }
        if (copy.size() == 1) {
            return copy.getFirst();
        }
        return batch -> {
            RuntimeException failure = null;
            for (EventSink sink : copy) {
                try {
                    sink.publish(batch);
                } catch (RuntimeException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        };
    }
}
