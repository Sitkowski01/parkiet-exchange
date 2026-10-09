package io.github.sitkowski01.exchange.persistence;

import io.github.sitkowski01.exchange.domain.BookEvent;
import io.github.sitkowski01.exchange.domain.BookEvent.OrderCancelled;
import io.github.sitkowski01.exchange.domain.Side;
import io.github.sitkowski01.exchange.engine.EngineEvent;
import io.github.sitkowski01.exchange.engine.EventSink;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Zapisuje zdarzenia silnika do tabeli {@code engine_event}.
 *
 * <p>Paczka zdarzen jednego polecenia idzie w jednej transakcji: albo wszystkie zdarzenia
 * zlecenia (np. dwie transakcje i reszta w arkuszu), albo zadne. Nigdy polowa.
 *
 * <p>Idempotentny ({@code on conflict do nothing}): commit, ktorego potwierdzenie zginelo w sieci,
 * jest ponawiany przez {@code AsyncEventSink} i nie moze zablokowac dziennika konfliktem klucza.
 * Klucz (przebieg, spolka, numer) nadaje silnik, wiec konflikt oznacza wylacznie powtorke.
 */
public final class JdbcEventSink implements EventSink {

    private static final String INSERT = """
            insert into engine_event (run_id, symbol, sequence, occurred_at, type, order_id,
                                      maker_order_id, taker_order_id, side, price, quantity, reason)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (run_id, symbol, sequence) do nothing""";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final long runId;

    private JdbcEventSink(JdbcTemplate jdbc, TransactionTemplate tx, long runId) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.runId = runId;
    }

    /** Otwiera nowy przebieg gieldy ({@code engine_run}) i zwraca odbiorce, ktory do niego pisze. */
    public static JdbcEventSink startRun(JdbcTemplate jdbc, TransactionTemplate tx) {
        Long runId = jdbc.queryForObject("insert into engine_run default values returning id", Long.class);
        return new JdbcEventSink(jdbc, tx, runId);
    }

    public long runId() {
        return runId;
    }

    @Override
    public void publish(List<EngineEvent> batch) {
        List<Object[]> rows = new ArrayList<>(batch.size());
        for (EngineEvent event : batch) {
            rows.add(row(event));
        }
        tx.executeWithoutResult(status -> jdbc.batchUpdate(INSERT, rows));
    }

    /** Sealed switch: nowy typ zdarzenia w domenie nie skompiluje sie, dopoki nie dopiszemy go tutaj. */
    private Object[] row(EngineEvent e) {
        return switch (e.event()) {
            case BookEvent.OrderRested r ->
                    row(e, "RESTED", r.orderId(), null, null, r.side(), r.price(), r.quantity(), null);
            case BookEvent.Trade t ->
                    row(e, "TRADE", null, t.makerOrderId(), t.takerOrderId(), t.takerSide(), t.price(), t.quantity(), null);
            case BookEvent.OrderCancelled c ->
                    row(e, "CANCELLED", c.orderId(), null, null, null, null, c.quantity(), c.reason());
        };
    }

    private Object[] row(EngineEvent e, String type, Long orderId, Long makerOrderId, Long takerOrderId,
                         Side side, Long price, long quantity, OrderCancelled.Reason reason) {
        // OffsetDateTime w UTC, nie java.sql.Timestamp: Timestamp przechodzi przez strefe czasowa JVM.
        return new Object[]{runId, e.symbol(), e.sequence(), OffsetDateTime.ofInstant(e.timestamp(), ZoneOffset.UTC),
                type, orderId, makerOrderId, takerOrderId, side == null ? null : side.name(), price, quantity,
                reason == null ? null : reason.name()};
    }
}
