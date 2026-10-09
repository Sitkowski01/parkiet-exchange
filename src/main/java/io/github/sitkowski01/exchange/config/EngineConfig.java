package io.github.sitkowski01.exchange.config;

import io.github.sitkowski01.exchange.engine.EventSink;
import io.github.sitkowski01.exchange.engine.MatchingEngine;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.Optional;

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
     * zanim aplikacja sie wylaczy. Bez odbiorcy zdarzen w kontekscie zdarzenia nigdzie nie ida.
     *
     * <p>{@code Optional}, a nie {@code ObjectProvider}: tylko tak Spring zapisuje, ze silnik
     * zalezy od odbiorcy, i zamyka silnik pierwszy. Inaczej zdarzenia ostatnich polecen
     * trafilyby do juz zamknietego odbiorcy (pilnuje tego test w MiniExchangeApplicationTests).
     */
    @Bean(destroyMethod = "close")
    MatchingEngine matchingEngine(ExchangeProperties properties, Clock clock, Optional<EventSink> sink) {
        return new MatchingEngine(properties.symbols(), properties.queueCapacity(), clock,
                sink.orElse(EventSink.NONE));
    }
}
