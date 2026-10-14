package io.github.sitkowski01.exchange.config;

/**
 * Biezacy przebieg gieldy. Numer zlecenia jest unikalny tylko w przebiegu: start bez wznowienia zaczyna
 * nowy przebieg z pustymi arkuszami i numeracja od 1. Klient (broker) zapisuje przebieg razem z numerem
 * zlecenia, zeby po takim starcie nie pomylic swojego starego zlecenia z cudzym nowym.
 *
 * @param id ten sam numer co {@code runId} w dzienniku i w zdarzeniach w Kafce
 */
public record ExchangeRun(long id) {

    public ExchangeRun {
        if (id < 1) {
            throw new IllegalArgumentException("run id must be positive: " + id);
        }
    }
}
