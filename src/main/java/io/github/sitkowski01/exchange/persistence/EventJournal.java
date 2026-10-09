package io.github.sitkowski01.exchange.persistence;

import io.github.sitkowski01.exchange.domain.BookEvent;
import io.github.sitkowski01.exchange.domain.BookEvent.OrderCancelled;
import io.github.sitkowski01.exchange.domain.Side;
import io.github.sitkowski01.exchange.engine.EngineEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

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

    private final JdbcTemplate jdbc;

    public EventJournal(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Do {@code limit} zdarzen o id wiekszym niz {@code afterId}, najstarsze najpierw. */
    public List<StoredEvent> readAfter(long afterId, int limit) {
        return jdbc.query(READ_AFTER, ROW, afterId, limit);
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
