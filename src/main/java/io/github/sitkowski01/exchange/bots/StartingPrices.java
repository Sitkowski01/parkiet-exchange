package io.github.sitkowski01.exchange.bots;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Cena startowa kazdej spolki: najpierw prawdziwa z rynku, potem zapasowa z konfiguracji. */
final class StartingPrices {

    private static final System.Logger LOG = System.getLogger(StartingPrices.class.getName());

    private StartingPrices() {
    }

    /**
     * Pyta zrodlo o wszystkie spolki naraz (watek wirtualny na spolke): gdy zrodlo nie odpowiada,
     * start trwa jeden limit czasu, a nie szesc po kolei.
     *
     * @return cena w groszach dla spolek, ktore jakas maja; spolka bez zadnej ceny nie dostaje botow
     */
    static Map<String, Long> resolve(List<String> symbols, ReferencePriceSource source,
                                     Map<String, BigDecimal> fallbackZloty) throws InterruptedException {
        Map<String, Future<OptionalLong>> asked = new LinkedHashMap<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            symbols.forEach(symbol -> asked.put(symbol, pool.submit(() -> source.priceInGrosze(symbol))));
        }
        Map<String, Long> prices = new LinkedHashMap<>();
        for (String symbol : symbols) {
            OptionalLong market = market(asked.get(symbol));
            if (market.isPresent()) {
                prices.put(symbol, market.getAsLong());
                LOG.log(System.Logger.Level.INFO, symbol + ": starting price "
                        + BigDecimal.valueOf(market.getAsLong(), 2) + " PLN from market data");
            } else if (fallbackZloty.containsKey(symbol)) {
                prices.put(symbol, fallbackZloty.get(symbol).movePointRight(2).longValueExact());
                LOG.log(System.Logger.Level.INFO, symbol + ": starting price " + fallbackZloty.get(symbol)
                        + " PLN from configuration");
            } else {
                LOG.log(System.Logger.Level.WARNING, symbol + ": no starting price, bots will not trade it");
            }
        }
        return prices;
    }

    private static OptionalLong market(Future<OptionalLong> answer) throws InterruptedException {
        try {
            return answer.get();
        } catch (ExecutionException e) {
            // Zrodlo ma nie rzucac, ale gdyby -- traktujemy to jak brak ceny.
            return OptionalLong.empty();
        }
    }
}
