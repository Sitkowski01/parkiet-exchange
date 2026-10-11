package io.github.sitkowski01.exchange.metrics;

import io.github.sitkowski01.exchange.domain.BookEvent;
import io.github.sitkowski01.exchange.domain.Side;
import io.github.sitkowski01.exchange.engine.EngineEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TradingMetricsTest {

    private static final Instant NOW = Instant.parse("2026-10-11T09:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final TradingMetrics metrics = new TradingMetrics(registry, List.of("CDR", "PKO"));

    @Test
    void countsEventsSharesTurnoverAndLastPrice() {
        metrics.publish(List.of(
                event("CDR", new BookEvent.OrderRested(1, Side.SELL, 26310, 10)),
                event("CDR", new BookEvent.Trade(1, 2, Side.BUY, 26310, 4)),
                event("CDR", new BookEvent.Trade(1, 3, Side.BUY, 26305, 2)),
                event("CDR", new BookEvent.OrderCancelled(1, 4, BookEvent.OrderCancelled.Reason.REQUESTED)),
                event("PKO", new BookEvent.OrderCancelled(5, 1, BookEvent.OrderCancelled.Reason.NO_LIQUIDITY))));

        assertThat(count("exchange.events", "CDR", "RESTED")).isEqualTo(1);
        assertThat(count("exchange.events", "CDR", "TRADE")).isEqualTo(2);
        assertThat(count("exchange.events", "CDR", "CANCELLED")).isEqualTo(1);
        assertThat(count("exchange.events", "PKO", "CANCELLED")).isEqualTo(1);
        assertThat(registry.get("exchange.traded.shares").tag("symbol", "CDR").counter().count()).isEqualTo(6);
        // 4 x 263,10 + 2 x 263,05 = 1052,40 + 526,10
        assertThat(registry.get("exchange.traded.value").tag("symbol", "CDR").counter().count()).isEqualTo(1578.5);
        assertThat(registry.get("exchange.last.price").tag("symbol", "CDR").gauge().value()).isEqualTo(263.05);
        assertThat(registry.get("exchange.last.price").tag("symbol", "PKO").gauge().value()).isZero();
    }

    @Test
    void unknownSymbolIsIgnored() {
        metrics.publish(List.of(event("XYZ", new BookEvent.Trade(1, 2, Side.BUY, 100, 1))));

        assertThat(registry.find("exchange.events").tag("symbol", "XYZ").counter()).isNull();
    }

    private double count(String name, String symbol, String type) {
        return registry.get(name).tag("symbol", symbol).tag("type", type).counter().count();
    }

    private static EngineEvent event(String symbol, BookEvent event) {
        return new EngineEvent(symbol, 1, NOW, event);
    }
}
