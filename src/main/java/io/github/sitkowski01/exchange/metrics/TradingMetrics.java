package io.github.sitkowski01.exchange.metrics;

import io.github.sitkowski01.exchange.domain.BookEvent;
import io.github.sitkowski01.exchange.engine.EngineEvent;
import io.github.sitkowski01.exchange.engine.EventSink;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metryki handlu liczone ze zdarzen silnika: ile zdarzen kazdego typu, ile akcji i za ile
 * zlotych zmienilo wlasciciela, ostatnia cena.
 *
 * <p>Wola go watek silnika, wiec musi byc tani: liczniki powstaja raz przy starcie, a
 * {@code increment} to dodawanie bez blokad. Zadnego szukania licznika po nazwie w goracej petli.
 */
public final class TradingMetrics implements EventSink {

    private record SymbolMeters(Counter rested, Counter trades, Counter cancelled, Counter shares, Counter value,
                                AtomicLong lastPrice) {
    }

    private final Map<String, SymbolMeters> meters = new HashMap<>();

    public TradingMetrics(MeterRegistry registry, Collection<String> symbols) {
        for (String symbol : symbols) {
            AtomicLong lastPrice = new AtomicLong();
            Gauge.builder("exchange.last.price", lastPrice, p -> p.get() / 100.0)
                    .tag("symbol", symbol).baseUnit("pln").description("Cena ostatniej transakcji")
                    .register(registry);
            meters.put(symbol, new SymbolMeters(
                    events(registry, symbol, "RESTED"),
                    events(registry, symbol, "TRADE"),
                    events(registry, symbol, "CANCELLED"),
                    Counter.builder("exchange.traded.shares").tag("symbol", symbol)
                            .description("Akcje, ktore zmienily wlasciciela").register(registry),
                    Counter.builder("exchange.traded.value").tag("symbol", symbol).baseUnit("pln")
                            .description("Obrot w zlotowkach").register(registry),
                    lastPrice));
        }
    }

    private static Counter events(MeterRegistry registry, String symbol, String type) {
        return Counter.builder("exchange.events").tag("symbol", symbol).tag("type", type)
                .description("Zdarzenia arkusza").register(registry);
    }

    @Override
    public void publish(List<EngineEvent> batch) {
        for (EngineEvent event : batch) {
            SymbolMeters m = meters.get(event.symbol());
            if (m == null) {
                continue;
            }
            switch (event.event()) {
                case BookEvent.OrderRested r -> m.rested().increment();
                case BookEvent.OrderCancelled c -> m.cancelled().increment();
                case BookEvent.Trade t -> {
                    m.trades().increment();
                    m.shares().increment(t.quantity());
                    // Obrot w groszach jako long (bez bledow double), na zlotowki dopiero przy liczniku.
                    m.value().increment(t.price() * t.quantity() / 100.0);
                    m.lastPrice().set(t.price());
                }
            }
        }
    }
}
