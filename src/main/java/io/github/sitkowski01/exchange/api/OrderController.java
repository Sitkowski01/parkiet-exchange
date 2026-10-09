package io.github.sitkowski01.exchange.api;

import io.github.sitkowski01.exchange.api.Views.BookView;
import io.github.sitkowski01.exchange.api.Views.OrderView;
import io.github.sitkowski01.exchange.config.ExchangeProperties;
import io.github.sitkowski01.exchange.engine.InstrumentEngine;
import io.github.sitkowski01.exchange.engine.MatchingEngine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Set;

/**
 * Cienka warstwa HTTP: zamienia JSON na wywolanie silnika i z powrotem.
 * Zadnej logiki gieldowej -- ta jest w domenie.
 */
@RestController
@RequestMapping("/api/instruments")
class OrderController {

    private final MatchingEngine engine;
    private final Duration timeout;

    OrderController(MatchingEngine engine, ExchangeProperties properties) {
        this.engine = engine;
        this.timeout = properties.requestTimeout();
    }

    @GetMapping
    Set<String> instruments() {
        return engine.symbols();
    }

    @PostMapping("/{symbol}/orders")
    @ResponseStatus(HttpStatus.CREATED)
    OrderView place(@PathVariable String symbol, @Valid @RequestBody PlaceOrderRequest request) {
        // Najpierw zasob (404), potem tresc (400) -- jak w kazdym REST API.
        InstrumentEngine instrument = engine.instrument(symbol);
        long price = request.priceInGrosze();
        return OrderView.of(EngineCalls.await(
                instrument.place(request.side(), request.type(), price, request.quantity()), timeout));
    }

    @DeleteMapping("/{symbol}/orders/{orderId}")
    EventView cancel(@PathVariable String symbol, @PathVariable long orderId) {
        return EngineCalls.await(engine.instrument(symbol).cancel(orderId), timeout)
                .map(EventView::of)
                .orElseThrow(() -> new OrderNotFoundException(symbol, orderId));
    }

    @GetMapping("/{symbol}/book")
    BookView book(@PathVariable String symbol,
                  @RequestParam(defaultValue = "10") @Min(1) @Max(100) int levels) {
        return BookView.of(EngineCalls.await(engine.instrument(symbol).snapshot(levels), timeout));
    }
}
