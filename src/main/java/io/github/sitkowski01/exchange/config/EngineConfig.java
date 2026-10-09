package io.github.sitkowski01.exchange.config;

import io.github.sitkowski01.exchange.engine.AsyncEventSink;
import io.github.sitkowski01.exchange.engine.EventSink;
import io.github.sitkowski01.exchange.engine.MatchingEngine;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;

import java.time.Clock;
import java.util.List;

/**
 * Tu Spring sklada silnik. Sam silnik nic o Springu nie wie (pilnuje tego ArchitectureTest).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExchangeProperties.class)
class EngineConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * {@code close} przy zamykaniu kontekstu: silnik dokonczy zlecenia z kolejki,
     * zanim aplikacja sie wylaczy. Odbiorcy oznaczeni {@link EngineSink} (dziennik, notowania)
     * dostaja kazda paczke zdarzen; bez nich zdarzenia nigdzie nie ida.
     *
     * <p>Lista wstrzykiwana wprost, a nie przez {@code ObjectProvider}: tylko tak Spring zapisuje,
     * ze silnik zalezy od odbiorcow, i zamyka silnik pierwszy. Inaczej zdarzenia ostatnich polecen
     * trafilyby do juz zamknietego odbiorcy (pilnuje tego test w MiniExchangeApplicationTests).
     */
    @Bean(destroyMethod = "close")
    MatchingEngine matchingEngine(ExchangeProperties properties, Clock clock, @EngineSink List<EventSink> sinks) {
        return new MatchingEngine(properties.symbols(), properties.queueCapacity(), clock, EventSink.fanOut(sinks));
    }

    /**
     * Zegar zamykania odbiorcow rusza, zanim Spring zacznie zamykac beany. Silnik zamyka sie przed
     * nimi, a jego watek moze wisiec w {@code publish} na pelnej kolejce -- bez tego zegara martwa
     * baza zablokowalaby wylaczenie aplikacji na zawsze.
     */
    @Bean
    ApplicationListener<ContextClosedEvent> sinkShutdownClock(ObjectProvider<AsyncEventSink> sinks) {
        return event -> sinks.orderedStream().forEach(AsyncEventSink::beginShutdown);
    }
}
