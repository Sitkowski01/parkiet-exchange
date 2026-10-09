package io.github.sitkowski01.exchange.marketdata;

import io.github.sitkowski01.exchange.domain.BookEvent;
import io.github.sitkowski01.exchange.domain.PriceLevel;
import io.github.sitkowski01.exchange.engine.BookSnapshot;
import io.github.sitkowski01.exchange.engine.EngineEvent;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Wiadomosci strumienia notowan. Ceny jako tekst w zlotowkach, jak w REST API -- odbiorca
 * to przegladarka, a JavaScript zgubilby grosze w double.
 *
 * <p>{@code sequence} pozwala zszyc obraz arkusza ze zmianami: klient odrzuca kazde
 * zdarzenie o numerze nie wiekszym niz w ostatnim obrazie, bo ten juz je uwzglednia.
 */
final class MarketDataMessages {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MarketDataMessages() {
    }

    record Level(String price, long quantity, int orders) {
    }

    record Book(String type, String symbol, long sequence, List<Level> bids, List<Level> asks) {
    }

    record Trade(String type, String symbol, long sequence, Instant timestamp, String price, long quantity,
                 String takerSide) {
    }

    static String book(BookSnapshot snapshot) {
        return JSON.writeValueAsString(new Book("book", snapshot.symbol(), snapshot.sequence(),
                levels(snapshot.bids()), levels(snapshot.asks())));
    }

    static String trade(EngineEvent event, BookEvent.Trade trade) {
        return JSON.writeValueAsString(new Trade("trade", event.symbol(), event.sequence(), event.timestamp(),
                zloty(trade.price()), trade.quantity(), trade.takerSide().name()));
    }

    private static List<Level> levels(List<PriceLevel> levels) {
        return levels.stream().map(l -> new Level(zloty(l.price()), l.quantity(), l.orders())).toList();
    }

    private static String zloty(long grosze) {
        return BigDecimal.valueOf(grosze, 2).toPlainString();
    }
}
