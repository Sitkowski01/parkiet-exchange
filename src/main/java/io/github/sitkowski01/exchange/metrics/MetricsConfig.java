package io.github.sitkowski01.exchange.metrics;

import io.github.sitkowski01.exchange.config.EngineSink;
import io.github.sitkowski01.exchange.config.ExchangeProperties;
import io.github.sitkowski01.exchange.engine.AsyncEventSink;
import io.github.sitkowski01.exchange.engine.MatchingEngine;
import io.github.sitkowski01.exchange.marketdata.MarketDataHub;
import io.github.sitkowski01.exchange.messaging.OutboxRelay;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metryki dla Prometheusa ({@code /actuator/prometheus}). Kazda odpowiada na pytanie
 * "gdzie sie korkuje": kolejka silnika, zapis do bazy, outbox do Kafki, notowania.
 *
 * <pre>
 * HTTP ──► [kolejka silnika] ──► silnik ──► [kolejka zapisu] ──► baza ──► [lag outboxa] ──► Kafka
 *          engine.queue.depth              journal.pending              outbox.lag
 * </pre>
 *
 * <p>Same klasy silnika nie wiedza o Micrometerze (ArchitectureTest) -- wystawiaja zwykle
 * gettery, a mierniki podpinamy tutaj.
 */
@Configuration(proxyBeanMethods = false)
class MetricsConfig {

    /** {@link EngineSink}: dostaje kazda paczke zdarzen prosto z silnika. */
    @Bean
    @EngineSink
    TradingMetrics tradingMetrics(MeterRegistry registry, ExchangeProperties exchange) {
        return new TradingMetrics(registry, exchange.symbols());
    }

    @Bean
    MeterBinder pipelineMetrics(MatchingEngine engine, AsyncEventSink journal, MarketDataHub hub,
                                OutboxRelay outbox) {
        return registry -> {
            for (String symbol : engine.symbols()) {
                Gauge.builder("exchange.engine.queue.depth", engine.instrument(symbol), i -> i.queueSize())
                        .tag("symbol", symbol).description("Polecenia czekajace na watek silnika")
                        .register(registry);
            }
            Gauge.builder("exchange.journal.pending", journal, AsyncEventSink::pending)
                    .description("Paczki zdarzen czekajace na zapis do bazy").register(registry);
            Gauge.builder("exchange.outbox.lag", outbox, OutboxRelay::lag)
                    .description("Zdarzenia z dziennika, ktore jeszcze nie poszly do Kafki").register(registry);
            Gauge.builder("exchange.marketdata.dropped", hub, MarketDataHub::droppedBatches)
                    .description("Paczki zgubione przez notowania (dziennik ich nie gubi)").register(registry);
        };
    }
}
