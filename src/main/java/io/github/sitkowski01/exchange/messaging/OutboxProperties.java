package io.github.sitkowski01.exchange.messaging;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Ustawienia relaya ({@code exchange.outbox}), sprawdzane przy starcie: {@code batch-size: 0}
 * zamienilby petle relaya w nieskonczona, a zerowy limit czasu -- w wieczne bledy.
 *
 * @param sendTimeout ile najwyzej trwa wysylka jednej paczki, razem z czekaniem na potwierdzenia
 */
@Validated
@ConfigurationProperties("exchange.outbox")
public record OutboxProperties(
        @NotNull @DurationMin(millis = 1) Duration pollInterval,
        @Positive int batchSize,
        @NotNull @DurationMin(millis = 1) Duration sendTimeout) {
}
