package io.github.sitkowski01.exchange.messaging;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.sitkowski01.exchange.domain.BookEvent;
import io.github.sitkowski01.exchange.engine.EngineEvent;

import java.time.Instant;

/**
 * Zdarzenie gieldy w Kafce (topic {@code exchange.events}, klucz = spolka). Kontrakt dla innych
 * serwisow (quote-stream, broker-ledger), wiec pola sa nazwane wprost, bez dwuznacznosci z bazy.
 *
 * <p>Dostawa co najmniej raz: ta sama wiadomosc moze przyjsc dwa razy. Odbiorca rozpoznaje
 * powtorke po {@code (runId, symbol, sequence)} -- w obrebie spolki numery rosna bez dziur.
 *
 * @param price     cena w groszach (liczba calkowita) -- serwisy licza na groszach, zlotowki sa dla ludzi
 * @param side      strona zlecenia w RESTED
 * @param takerSide strona aktywnego zlecenia w TRADE (maker ma przeciwna)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventMessage(
        long runId,
        String symbol,
        long sequence,
        Instant timestamp,
        String type,
        Long orderId,
        Long makerOrderId,
        Long takerOrderId,
        String side,
        String takerSide,
        Long price,
        long quantity,
        String reason) {

    /** Sealed switch: nowy typ zdarzenia w domenie nie skompiluje sie, dopoki nie trafi do kontraktu. */
    static EventMessage of(long runId, EngineEvent e) {
        return switch (e.event()) {
            case BookEvent.OrderRested r -> new EventMessage(runId, e.symbol(), e.sequence(), e.timestamp(),
                    "RESTED", r.orderId(), null, null, r.side().name(), null, r.price(), r.quantity(), null);
            case BookEvent.Trade t -> new EventMessage(runId, e.symbol(), e.sequence(), e.timestamp(),
                    "TRADE", null, t.makerOrderId(), t.takerOrderId(), null, t.takerSide().name(), t.price(),
                    t.quantity(), null);
            case BookEvent.OrderCancelled c -> new EventMessage(runId, e.symbol(), e.sequence(), e.timestamp(),
                    "CANCELLED", c.orderId(), null, null, null, null, null, c.quantity(), c.reason().name());
        };
    }
}
