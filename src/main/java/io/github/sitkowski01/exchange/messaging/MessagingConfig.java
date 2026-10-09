package io.github.sitkowski01.exchange.messaging;

import io.github.sitkowski01.exchange.config.ExchangeProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <pre>
 * engine_event (id &gt; outbox_cursor) ──OutboxRelay co 100 ms──► Kafka: exchange.events, klucz = spolka
 * </pre>
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
class MessagingConfig {

    static final String TOPIC = "exchange.events";

    /**
     * Klucz = spolka, wiec spolka siedzi na jednej partycji (tylko tak Kafka gwarantuje kolejnosc),
     * a wiecej partycji niz spolek nic nie da. Jedna replika wystarcza lokalnie; na klastrze 3.
     *
     * <p>Topic zaklada KafkaAdmin przy starcie. Gdy brokera wtedy nie ma, broker zalozy go sam
     * przy pierwszej wiadomosci -- z wlasna, domyslna liczba partycji.
     */
    @Bean
    NewTopic exchangeEvents(ExchangeProperties exchange) {
        return TopicBuilder.name(TOPIC).partitions(exchange.symbols().size()).replicas(1).build();
    }

    @Bean
    MessagePublisher kafkaPublisher(KafkaTemplate<String, String> kafka) {
        return (key, value) -> kafka.send(TOPIC, key, value);
    }

    @Bean
    OutboxRelay outboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, MessagePublisher publisher,
                            OutboxProperties outbox) {
        return new OutboxRelay(jdbc, tx, publisher, TOPIC, outbox.batchSize(), outbox.sendTimeout());
    }
}
