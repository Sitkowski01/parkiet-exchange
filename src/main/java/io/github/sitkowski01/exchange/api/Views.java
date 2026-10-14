package io.github.sitkowski01.exchange.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.sitkowski01.exchange.domain.PriceLevel;
import io.github.sitkowski01.exchange.engine.BookSnapshot;
import io.github.sitkowski01.exchange.engine.ClientOrderId;
import io.github.sitkowski01.exchange.engine.OrderResult;

import java.math.BigDecimal;
import java.util.List;

/** Odpowiedzi API. Rekordy Jackson zamienia na JSON bez zadnej konfiguracji. */
final class Views {

    private Views() {
    }

    /**
     * Bez {@code clientOrderId} i z {@code repeated = false} odpowiedz wyglada jak dawniej -- starzy klienci
     * nic nie zauwaza.
     *
     * @param runId    przebieg gieldy: numer zlecenia jest unikalny tylko w nim (start bez wznowienia liczy od 1)
     * @param repeated {@code true}: zlecenie z tym {@code clientOrderId} juz bylo -- to jego pierwotny wynik,
     *                 arkusz sie nie zmienil
     */
    record OrderView(long orderId, long runId, @JsonInclude(JsonInclude.Include.NON_NULL) String clientOrderId,
                     @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean repeated, List<EventView> events) {

        static OrderView of(OrderResult result, long runId, ClientOrderId clientOrderId) {
            return new OrderView(result.orderId(), runId, ClientOrderId.valueOrNull(clientOrderId),
                    result.repeated(), result.events().stream().map(EventView::of).toList());
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
