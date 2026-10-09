package io.github.sitkowski01.exchange;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Prawdziwa Kafka w Dockerze -- ten sam obraz co w compose.yaml. Osobno od Postgresa,
 * zeby testy samej bazy (@JdbcTest) nie czekaly na start brokera.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaContainerConfiguration {

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer("apache/kafka-native:4.2.1");
    }
}
