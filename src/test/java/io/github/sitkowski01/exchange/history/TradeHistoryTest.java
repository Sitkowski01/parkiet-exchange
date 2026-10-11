package io.github.sitkowski01.exchange.history;

import io.github.sitkowski01.exchange.TestcontainersConfiguration;
import io.github.sitkowski01.exchange.domain.BookEvent;
import io.github.sitkowski01.exchange.domain.Side;
import io.github.sitkowski01.exchange.engine.EngineEvent;
import io.github.sitkowski01.exchange.history.TradeHistory.Candle;
import io.github.sitkowski01.exchange.persistence.JdbcEventSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TradeHistoryTest {

    private static final Instant T0 = Instant.parse("2026-10-11T09:00:00Z");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    private TradeHistory history;
    private long sequence;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate engine_event, book_snapshot, engine_run");
        history = new TradeHistory(jdbc);
    }

    @Test
    void groupsTradesIntoOhlcCandles() {
        JdbcEventSink sink = JdbcEventSink.startRun(jdbc, tx);
        sink.publish(List.of(
                trade("CDR", T0.plusMillis(100), 26300, 5),
                rested("CDR", T0.plusMillis(200), 26400),          // nie transakcja -- nie wchodzi do swiec
                trade("CDR", T0.plusMillis(300), 26450, 1),
                trade("CDR", T0.plusMillis(300), 26250, 2),         // ten sam znacznik czasu: liczy sie kolejnosc
                trade("CDR", T0.plusSeconds(4), 26280, 3),
                trade("PKO", T0.plusSeconds(1), 11394, 100),        // inna spolka
                trade("CDR", T0.plusSeconds(12), 26310, 4)));       // przedzial 10-15 s; 5-10 s bez transakcji

        List<Candle> candles = history.candles("CDR", Duration.ofSeconds(5), 4, T0.plusSeconds(13));

        assertThat(candles).containsExactly(
                new Candle(T0, 26300, 26450, 26250, 26280, 11),
                new Candle(T0.plusSeconds(10), 26310, 26310, 26310, 26310, 4));
    }

    @Test
    void windowStartsAtBucketBoundary() {
        JdbcEventSink sink = JdbcEventSink.startRun(jdbc, tx);
        sink.publish(List.of(
                trade("CDR", T0.minusSeconds(1), 100, 1),           // przed oknem
                trade("CDR", T0, 200, 1),                           // poczatek najstarszego przedzialu
                trade("CDR", T0.plusSeconds(9), 300, 1)));

        // Teraz = 9 s po T0, 2 przedzialy po 5 s: [T0, T0+5) i [T0+5, T0+10).
        List<Candle> candles = history.candles("CDR", Duration.ofSeconds(5), 2, T0.plusSeconds(9));

        assertThat(candles).extracting(Candle::open).containsExactly(200L, 300L);
    }

    @Test
    void intervalShorterThanSecondIsRejected() {
        assertThatThrownBy(() -> history.candles("CDR", Duration.ofMillis(500), 10, T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private EngineEvent trade(String symbol, Instant at, long price, long quantity) {
        return new EngineEvent(symbol, ++sequence, at, new BookEvent.Trade(1, 2, Side.BUY, price, quantity));
    }

    private EngineEvent rested(String symbol, Instant at, long price) {
        return new EngineEvent(symbol, ++sequence, at, new BookEvent.OrderRested(9, Side.SELL, price, 1));
    }
}
