package io.github.sitkowski01.exchange.persistence;

import io.github.sitkowski01.exchange.domain.BookEvent;
import io.github.sitkowski01.exchange.domain.BookEvent.OrderCancelled;
import io.github.sitkowski01.exchange.domain.Side;
import io.github.sitkowski01.exchange.engine.EngineEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Odczyt dziennika w kolejnosci zapisu. Lezy obok {@link JdbcEventSink}, bo musi znac ten sam
 * format wiersza -- zmiana jednego bez drugiego wywali test zapisu i odczytu w obie strony.
 */
public final class EventJournal {

    /** Zdarzenie z dziennika z jego miejscem: {@code id} to globalna kolejnosc zapisu. */
    public record StoredEvent(long id, long runId, EngineEvent event) {
    }

    private static final String READ_AFTER = """
            select id, run_id, symbol, sequence, occurred_at, type, order_id, maker_order_id, taker_order_id,
                   side, price, quantity, reason
            from engine_event
            where id > ?
            order by id
            limit ?""";

    private static final RowMapper<StoredEvent> ROW = (rs, n) -> new StoredEvent(rs.getLong("id"),
            rs.getLong("run_id"), new EngineEvent(rs.getString("symbol"), rs.getLong("sequence"),
            rs.getObject("occurred_at", OffsetDateTime.class).toInstant(), bookEvent(rs)));

    private static final String REPLAY = """
            select id, run_id, symbol, sequence, occurred_at, type, order_id, maker_order_id, taker_order_id,
                   side, price, quantity, reason
            from engine_event
            where run_id = ? and symbol = ?
            order by sequence""";

    private final JdbcTemplate jdbc;
    private final JdbcTemplate streaming;
    private final TransactionTemplate tx;

    public EventJournal(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.streaming = new JdbcTemplate(jdbc.getDataSource());
        this.streaming.setFetchSize(10_000);
    }

    /** Do {@code limit} zdarzen o id wiekszym niz {@code afterId}, najstarsze najpierw. */
    public List<StoredEvent> readAfter(long afterId, int limit) {
        return jdbc.query(READ_AFTER, ROW, afterId, limit);
    }

    /** Ostatni przebieg gieldy; pusto, gdy gielda jeszcze nigdy nie startowala. */
    public OptionalLong lastRun() {
        Long id = jdbc.queryForObject("select max(id) from engine_run", Long.class);
        return id == null ? OptionalLong.empty() : OptionalLong.of(id);
    }

    /**
     * Przekazuje po kolei wszystkie zdarzenia spolki z danego przebiegu, w kolejnosci {@code sequence}.
     * Strumieniowo (kursor po stronie bazy): caly dzien handlu nie musi zmiescic sie w pamieci naraz.
     * PostgreSQL trzyma kursor tylko w transakcji -- poza nia sterownik wczytalby wszystko od razu.
     */
    public void replay(long runId, String symbol, Consumer<EngineEvent> consumer) {
        tx.executeWithoutResult(status -> {
            try (Stream<StoredEvent> events = streaming.queryForStream(REPLAY, ROW, runId, symbol)) {
                events.forEach(stored -> consumer.accept(stored.event()));
            }
        });
    }

    private static BookEvent bookEvent(ResultSet rs) throws SQLException {
        String type = rs.getString("type");
        return switch (type) {
            case "RESTED" -> new BookEvent.OrderRested(rs.getLong("order_id"), Side.valueOf(rs.getString("side")),
                    rs.getLong("price"), rs.getLong("quantity"));
            case "TRADE" -> new BookEvent.Trade(rs.getLong("maker_order_id"), rs.getLong("taker_order_id"),
                    Side.valueOf(rs.getString("side")), rs.getLong("price"), rs.getLong("quantity"));
            case "CANCELLED" -> new OrderCancelled(rs.getLong("order_id"), rs.getLong("quantity"),
                    OrderCancelled.Reason.valueOf(rs.getString("reason")));
            default -> throw new IllegalStateException("unknown event type in journal: " + type);
        };
    }
}
