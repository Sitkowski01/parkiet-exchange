package io.github.sitkowski01.exchange;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Prawdziwy PostgreSQL w Dockerze na czas testow. {@code @ServiceConnection} podaje Springowi
 * adres, uzytkownika i haslo kontenera -- zadnych {@code spring.datasource.*} w testach.
 *
 * <p>Ta sama wersja co w compose.yaml: testy na H2 przepuscilyby SQL, ktorego Postgres nie zna
 * (i odwrotnie), a {@code check}-i z migracji w ogole nie bylyby sprawdzone.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17-alpine");
    }
}
