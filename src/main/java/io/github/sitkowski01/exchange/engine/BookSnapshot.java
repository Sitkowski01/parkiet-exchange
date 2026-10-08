package io.github.sitkowski01.exchange.engine;

import io.github.sitkowski01.exchange.domain.PriceLevel;

import java.util.List;

/**
 * Spojny obraz arkusza w jednej chwili.
 *
 * @param sequence numer ostatniego zdarzenia, ktore ten obraz juz uwzglednia
 */
public record BookSnapshot(String symbol, long sequence, List<PriceLevel> bids, List<PriceLevel> asks) {
}
