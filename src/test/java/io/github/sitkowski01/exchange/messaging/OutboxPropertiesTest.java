package io.github.sitkowski01.exchange.messaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/** batch-size 0 krecilby petle relaya w nieskonczonosc -- ma wywrocic start, nie produkcje. */
class OutboxPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OutboxProperties.class)
    static class Props {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Props.class)
            .withPropertyValues("exchange.outbox.poll-interval=100ms", "exchange.outbox.batch-size=500",
                    "exchange.outbox.send-timeout=15s");

    @Test
    void validConfigurationStarts() {
        runner.run(context -> assertThat(context.getBean(OutboxProperties.class).batchSize()).isEqualTo(500));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "exchange.outbox.batch-size=0",
            "exchange.outbox.batch-size=-1",
            "exchange.outbox.send-timeout=0s",
            "exchange.outbox.poll-interval=0ms"
    })
    void invalidConfigurationFailsStartup(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }
}
