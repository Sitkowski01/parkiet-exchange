package io.github.sitkowski01.exchange.config;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Ustawienia gieldy z {@code application.yml} (prefiks {@code exchange}).
 *
 * <p>{@code @Validated} sprawdza je przy starcie: literowka w konfiguracji wywraca aplikacje
 * od razu, zamiast wyjsc na jaw przy pierwszym zleceniu.
 *
 * @param requestTimeout ile zadanie HTTP czeka na odpowiedz silnika
 */
@Validated
@ConfigurationProperties("exchange")
public record ExchangeProperties(
        @NotEmpty List<@Pattern(regexp = "[A-Z0-9]{1,10}") String> symbols,
        @Positive int queueCapacity,
        @NotNull @DurationMin(millis = 1) Duration requestTimeout) {
}
