package io.github.sitkowski01.exchange.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.github.sitkowski01.exchange.domain.PriceLevel;
import io.github.sitkowski01.exchange.engine.BookSnapshot;
import io.github.sitkowski01.exchange.engine.OrderResult;

import java.math.BigDecimal;
import java.util.List;

/** Odpowiedzi API. Rekordy Jackson zamienia na JSON bez zadnej konfiguracji. */
final class Views {

    private Views() {
    }

    record OrderView(long orderId, List<EventView> events) {

        static OrderView of(OrderResult result) {
            return new OrderView(result.orderId(), result.events().stream().map(EventView::of).toList());
        }
    }

    record LevelView(@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal price, long quantity, int orders) {

        static LevelView of(PriceLevel level) {
            return new LevelView(Prices.toZloty(level.price()), level.quantity(), level.orders());
        }
    }

    record BookView(String symbol, long sequence, List<LevelView> bids, List<LevelView> asks) {

        static BookView of(BookSnapshot snapshot) {
            return new BookView(snapshot.symbol(), snapshot.sequence(),
                    snapshot.bids().stream().map(LevelView::of).toList(),
                    snapshot.asks().stream().map(LevelView::of).toList());
        }
    }
}
