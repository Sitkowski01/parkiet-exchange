package io.github.sitkowski01.exchange.bots;

import io.github.sitkowski01.exchange.config.ExchangeProperties;
import io.github.sitkowski01.exchange.engine.EventSink;
import io.github.sitkowski01.exchange.engine.MatchingEngine;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BotsConfigTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ExchangeProperties.class)
    static class Exchange {

        @Bean(destroyMethod = "close")
        MatchingEngine matchingEngine() {
            return new MatchingEngine(List.of("CDR", "PKO"), 1000, Clock.systemUTC(), EventSink.NONE);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Exchange.class, BotsConfig.class)
            .withPropertyValues("exchange.symbols=CDR,PKO", "exchange.queue-capacity=1000",
                    "exchange.request-timeout=2s", "exchange.bots.tick-interval=1h", "exchange.bots.volatility=0.001",
                    "exchange.bots.spread-bps=20", "exchange.bots.maker-quantity=50",
                    "exchange.bots.noise-probability=0.5",
                    // Niedostepne zrodlo: boty musza wystartowac z cen zapasowych.
                    "exchange.bots.price-source-url=http://127.0.0.1:1",
                    "exchange.bots.fallback-prices.CDR=263.00");

    @Test
    void botsAreOffUnlessEnabled() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(BotRunner.class));
    }

    @Test
    void enabledBotsStartFromFallbackWhenMarketIsUnreachable() {
        runner.withPropertyValues("exchange.bots.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(BotRunner.class);
            assertThat(context.getBean(BotRunner.class).symbols()).containsExactly("CDR");
            assertThat(context.getBean(BotRunner.class).isRunning()).as("ruszyly po starcie kontekstu").isTrue();
        });
    }

    @Test
    void invalidSettingsFailStartup() {
        runner.withPropertyValues("exchange.bots.enabled=true", "exchange.bots.noise-probability=1.5")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void fallbackPriceWithFractionOfGroszFailsStartup() {
        runner.withPropertyValues("exchange.bots.enabled=true", "exchange.bots.fallback-prices.PKO=113.945")
                .run(context -> assertThat(context).hasFailed());
    }
}
