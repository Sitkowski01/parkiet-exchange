package io.github.sitkowski01.exchange.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Zla konfiguracja ma wywrocic start aplikacji, a nie wyjsc na jaw przy pierwszym zleceniu. */
class ExchangePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(EngineConfig.class)
            .withPropertyValues("exchange.symbols=CDR,PKO", "exchange.queue-capacity=10",
                    "exchange.request-timeout=1s");

    @Test
    void validConfigurationStartsEngine() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ExchangeProperties.class).requestTimeout()).isEqualTo(Duration.ofSeconds(1));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "exchange.request-timeout=0s",
            "exchange.request-timeout=500us",
            "exchange.queue-capacity=0",
            "exchange.symbols=cdr",
            "exchange.symbols="
    })
    void invalidConfigurationFailsStartup(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }
}
