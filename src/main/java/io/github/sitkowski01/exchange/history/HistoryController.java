package io.github.sitkowski01.exchange.history;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.github.sitkowski01.exchange.config.ExchangeProperties;
import io.github.sitkowski01.exchange.engine.UnknownInstrumentException;
import io.github.sitkowski01.exchange.history.TradeHistory.Candle;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * {@code GET /api/instruments/CDR/candles?seconds=5&count=120} -- swiece do wykresu.
 * Ceny jako tekst w zlotowkach, jak w reszcie API.
 */
@RestController
@RequestMapping("/api/instruments/{symbol}/candles")
class HistoryController {

    record CandleView(Instant time,
                      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal open,
                      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal high,
                      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal low,
                      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal close,
                      long volume) {

        static CandleView of(Candle c) {
            return new CandleView(c.time(), zloty(c.open()), zloty(c.high()), zloty(c.low()), zloty(c.close()),
                    c.volume());
        }

        private static BigDecimal zloty(long grosze) {
            return BigDecimal.valueOf(grosze, 2);
        }
    }

    /**
     * Najdluzszy zakres jednego zapytania. Swiece liczy jeden skan dziennika po calym zakresie --
     * bez limitu "3600 s x 1000" to 41 dni handlu w jednym zapytaniu od kogokolwiek z sieci.
     */
    static final Duration MAX_RANGE = Duration.ofHours(24);

    private final TradeHistory history;
    private final Set<String> symbols;
    private final Clock clock;

    HistoryController(TradeHistory history, ExchangeProperties exchange, Clock clock) {
        this.history = history;
        this.symbols = Set.copyOf(exchange.symbols());
        this.clock = clock;
    }

    @GetMapping
    List<CandleView> candles(@PathVariable String symbol,
                             @RequestParam(defaultValue = "5") @Min(1) @Max(3600) int seconds,
                             @RequestParam(defaultValue = "120") @Min(1) @Max(1000) int count) {
        if (!symbols.contains(symbol)) {
            throw new UnknownInstrumentException(symbol);
        }
        if ((long) seconds * count > MAX_RANGE.toSeconds()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "seconds x count must not exceed " + MAX_RANGE.toSeconds() + " (24 h), was " + (long) seconds * count);
        }
        return history.candles(symbol, Duration.ofSeconds(seconds), count, clock.instant()).stream()
                .map(CandleView::of).toList();
    }
}
