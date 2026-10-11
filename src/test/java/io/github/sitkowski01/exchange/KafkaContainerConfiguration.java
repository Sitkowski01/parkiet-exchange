package io.github.sitkowski01.exchange;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;

/**
 * Prawdziwa Kafka w Dockerze -- ten sam obraz co w compose.yaml. Osobno od Postgresa,
 * zeby testy samej bazy (@JdbcTest) nie czekaly na start brokera.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaContainerConfiguration {

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        // Na wspoldzielonym runnerze CI start potrafi przekroczyc domyslna minute (11.10: "Timed out waiting
        // for log output ... RECOVERY to RUNNING", ponowienie przeszlo). Wiecej czasu i druga proba.
        return new KafkaContainer("apache/kafka-native:4.2.1")
                .withStartupTimeout(Duration.ofMinutes(3))
                .withStartupAttempts(2);
    }
}
