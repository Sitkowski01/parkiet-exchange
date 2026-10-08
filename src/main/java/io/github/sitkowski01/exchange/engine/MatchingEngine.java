package io.github.sitkowski01.exchange.engine;

import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cala gielda: po jednym {@link InstrumentEngine} na spolke.
 *
 * <p>Instrumenty sa od siebie niezalezne, wiec skaluja sie liniowo z liczba rdzeni:
 * natlok na CDR nie spowalnia PKO.
 */
public final class MatchingEngine implements AutoCloseable {

    private final Map<String, InstrumentEngine> instruments;

    public MatchingEngine(List<String> symbols, int queueCapacity, Clock clock, EventSink sink) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queue capacity must be positive, was " + queueCapacity);
        }
        // Walidacja przed startem pierwszego watku, a gdyby start i tak padl w polowie --
        // sprzatanie juz uruchomionych. Inaczej zostalyby watki, ktorych nikt nie zamknie.
        if (new LinkedHashSet<>(symbols).size() != symbols.size()) {
            throw new IllegalArgumentException("duplicate symbols in " + symbols);
        }
        Map<String, InstrumentEngine> started = new LinkedHashMap<>();
        try {
            for (String symbol : symbols) {
                started.put(symbol, InstrumentEngine.start(symbol, queueCapacity, clock, sink));
            }
        } catch (RuntimeException | Error e) {
            started.values().forEach(InstrumentEngine::close);
            throw e;
        }
        this.instruments = Collections.unmodifiableMap(started);
    }

    public InstrumentEngine instrument(String symbol) {
        InstrumentEngine engine = instruments.get(symbol);
        if (engine == null) {
            throw new UnknownInstrumentException(symbol);
        }
        return engine;
    }

    public Set<String> symbols() {
        return instruments.keySet();
    }

    @Override
    public void close() {
        instruments.values().forEach(InstrumentEngine::close);
    }
}
