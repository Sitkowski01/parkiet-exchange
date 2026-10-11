package io.github.sitkowski01.exchange.persistence;

import io.github.sitkowski01.exchange.domain.BookEvent.OrderRested;
import io.github.sitkowski01.exchange.domain.OrderBook;
import io.github.sitkowski01.exchange.domain.Side;
import io.github.sitkowski01.exchange.engine.StateSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

/**
 * Migawki arkuszy w tabeli {@code book_snapshot}. Zlecenia jako JSON (jsonb): jedna migawka to jeden
 * wiersz, a format nie zalezy od rekordow domeny -- zmiana nazwy pola w {@link OrderRested} nie
 * uniewazni migawek zapisanych wczesniej.
 */
public final class SnapshotStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<Order>> ORDERS = new TypeReference<>() {
    };

    private record Order(long id, Side side, long price, long quantity) {
    }

    private final JdbcTemplate jdbc;

    public SnapshotStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Idempotentne: ta sama migawka drugi raz nic nie zmienia. Zostaje tylko najnowsza migawka spolki
     * i tylko z tego przebiegu -- start i tak czyta wylacznie najnowsza.
     */
    public void save(long runId, StateSnapshot snapshot) {
        List<Order> orders = snapshot.book().orders().stream()
                .map(o -> new Order(o.orderId(), o.side(), o.price(), o.quantity())).toList();
        jdbc.update("""
                insert into book_snapshot (run_id, symbol, sequence, last_order_id, orders)
                values (?, ?, ?, ?, ?::jsonb)
                on conflict (run_id, symbol, sequence) do nothing""",
                runId, snapshot.symbol(), snapshot.sequence(), snapshot.book().lastOrderId(),
                JSON.writeValueAsString(orders));
        jdbc.update("""
                delete from book_snapshot
                where (run_id = ? and symbol = ? and sequence < ?) or run_id <> ?""",
                runId, snapshot.symbol(), snapshot.sequence(), runId);
    }

    public Optional<StateSnapshot> latest(long runId, String symbol) {
        return jdbc.query("""
                        select sequence, last_order_id, orders from book_snapshot
                        where run_id = ? and symbol = ? order by sequence desc limit 1""",
                (rs, n) -> new StateSnapshot(symbol, rs.getLong("sequence"), new OrderBook.Snapshot(
                        JSON.readValue(rs.getString("orders"), ORDERS).stream()
                                .map(o -> new OrderRested(o.id(), o.side(), o.price(), o.quantity())).toList(),
                        rs.getLong("last_order_id"))),
                runId, symbol).stream().findFirst();
    }
}
