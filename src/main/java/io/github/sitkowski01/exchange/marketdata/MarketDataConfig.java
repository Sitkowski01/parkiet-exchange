package io.github.sitkowski01.exchange.marketdata;

import io.github.sitkowski01.exchange.config.EngineSink;
import io.github.sitkowski01.exchange.config.ExchangeProperties;
import io.github.sitkowski01.exchange.engine.MatchingEngine;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.time.Duration;
import java.util.Set;

/**
 * <pre>
 * silnik ──offer──► MarketDataHub (watek market-data) ──offer──► ClientConnection (watek klienta) ──► WebSocket
 *                        ▲
 *        BookBroadcaster co 100 ms: zmienione spolki ──► obraz arkusza
 * </pre>
 * Nigdzie po drodze nikt nie czeka na kogos wolniejszego od siebie.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@EnableConfigurationProperties(MarketDataConfig.MarketDataProperties.class)
class MarketDataConfig {

    /**
     * @param bookInterval co ile najwyzej idzie obraz arkusza spolki, ktora sie zmienila
     * @param clientQueue  ile wiadomosci moze czekac na klienta, zanim uznamy go za zbyt wolnego
     * @param hubQueue     ile paczek zdarzen moze czekac na hub, zanim zaczniemy je gubic
     */
    @Validated
    @ConfigurationProperties("exchange.market-data")
    record MarketDataProperties(
            @NotNull @DurationMin(millis = 1) Duration bookInterval,
            @Positive int bookLevels,
            @Positive int clientQueue,
            @Positive int hubQueue,
            @Positive int maxClients) {
    }

    /** {@link EngineSink}: silnik oddaje mu kazda paczke, a zamyka sie przed nim. */
    @Bean(destroyMethod = "close")
    @EngineSink
    MarketDataHub marketDataHub(MarketDataProperties props) {
        return new MarketDataHub(props.hubQueue());
    }

    @Bean(destroyMethod = "close")
    BookBroadcaster bookBroadcaster(MarketDataHub hub, MatchingEngine engine, MarketDataProperties props,
                                    ExchangeProperties exchange) {
        BookBroadcaster broadcaster = new BookBroadcaster(hub, engine, props.bookLevels(), exchange.requestTimeout());
        broadcaster.start(props.bookInterval());
        return broadcaster;
    }

    @Bean
    MarketDataHandler marketDataHandler(MarketDataHub hub, BookBroadcaster books, ExchangeProperties exchange,
                                        MarketDataProperties props) {
        return new MarketDataHandler(hub, books, Set.copyOf(exchange.symbols()), props.clientQueue(),
                props.maxClients());
    }

    @Bean
    WebSocketConfigurer marketDataEndpoint(MarketDataHandler handler) {
        // Notowania sa publiczne i tylko do odczytu, wiec wpuszczamy kazda strone (np. demo z innego portu).
        return (WebSocketHandlerRegistry registry) -> registry.addHandler(handler, "/ws/market")
                .setAllowedOrigins("*");
    }
}
