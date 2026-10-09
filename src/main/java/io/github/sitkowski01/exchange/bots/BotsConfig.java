package io.github.sitkowski01.exchange.bots;

import io.github.sitkowski01.exchange.config.ExchangeProperties;
import io.github.sitkowski01.exchange.engine.MatchingEngine;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Boty wlaczane wylacznie jawnie ({@code exchange.bots.enabled=true}, np. profil {@code demo}).
 * Domyslnie wylaczone: testy i prawdziwi klienci zakladaja, ze w arkuszu nie ma nikogo innego.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "exchange.bots.enabled", havingValue = "true")
@EnableConfigurationProperties(BotsConfig.BotProperties.class)
class BotsConfig {

    /**
     * @param volatility       odchylenie jednego kroku wartosci godziwej, np. 0.001 = 0,1% na tick
     * @param spreadBps        rozstep animatora w punktach bazowych
     * @param noiseProbability szansa, ze gracz losowy zlozy zlecenie w danym ticku
     * @param fallbackPrices   ceny startowe w zlotowkach, gdy zrodlo rynkowe nie odpowie
     */
    @Validated
    @ConfigurationProperties("exchange.bots")
    record BotProperties(
            @NotNull @DurationMin(millis = 10) Duration tickInterval,
            @DecimalMin("0") @DecimalMax("0.05") double volatility,
            @Positive int spreadBps,
            @Positive long makerQuantity,
            @DecimalMin("0") @DecimalMax("1") double noiseProbability,
            @NotNull String priceSourceUrl,
            @NotNull Map<String, @Positive @Digits(integer = 12, fraction = 2) BigDecimal> fallbackPrices) {
    }

    /** Ceny startowe pobierane raz; klient HTTP zamykany od razu, bo pozniej nie jest potrzebny. */
    @Bean(destroyMethod = "close")
    BotRunner botRunner(MatchingEngine engine, ExchangeProperties exchange, BotProperties props)
            throws InterruptedException {
        Map<String, Long> prices;
        try (YahooPriceSource source = new YahooPriceSource(props.priceSourceUrl(), Duration.ofSeconds(2))) {
            prices = StartingPrices.resolve(exchange.symbols(), source, props.fallbackPrices());
        }
        return new BotRunner(engine, prices,
                new BotRunner.BotSettings(props.volatility(), props.spreadBps(), props.makerQuantity(),
                        props.noiseProbability(), exchange.requestTimeout()),
                SplittableRandom::new);
    }

    /**
     * Boty ruszaja dopiero, gdy cala aplikacja wstala (inaczej handlowalyby w aplikacji, ktora
     * moze sie jeszcze wywrocic), i staja jako pierwsze przy zamykaniu -- przed silnikiem i zapisem.
     */
    @Bean
    SmartLifecycle botLifecycle(BotRunner runner, BotProperties props) {
        return new SmartLifecycle() {
            @Override
            public void start() {
                runner.start(props.tickInterval());
            }

            @Override
            public void stop() {
                runner.close();
            }

            @Override
            public boolean isRunning() {
                return runner.isRunning();
            }
        };
    }
}
