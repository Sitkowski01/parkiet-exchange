package io.github.sitkowski01.exchange.api;

import io.github.sitkowski01.exchange.api.Views.BookView;
import io.github.sitkowski01.exchange.api.Views.OrderView;
import io.github.sitkowski01.exchange.config.ExchangeProperties;
import io.github.sitkowski01.exchange.engine.InstrumentEngine;
import io.github.sitkowski01.exchange.engine.MatchingEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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
@Tag(name = "Zlecenia", description = "Składanie i anulowanie zleceń, arkusz spółki")
class OrderController {

    private static final String PROBLEM = "application/problem+json";

    private final MatchingEngine engine;
    private final Duration timeout;

    OrderController(MatchingEngine engine, ExchangeProperties properties) {
        this.engine = engine;
        this.timeout = properties.requestTimeout();
    }

    @GetMapping
    @Operation(summary = "Spółki notowane na giełdzie")
    Set<String> instruments() {
        return engine.symbols();
    }

    @PostMapping("/{symbol}/orders")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Złóż zlecenie",
            description = "Odpowiedź mówi, co dokładnie zrobił silnik: transakcje (TRADE), reszta w arkuszu "
                    + "(RESTED) albo reszta PKC bez ofert po drugiej stronie (CANCELLED).")
    @ApiResponse(responseCode = "201", description = "Zlecenie przyjęte i przetworzone")
    @ApiResponse(responseCode = "404", description = "Nieznana spółka",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "400", description = "Zła treść: ułamek grosza, LIMIT bez ceny, PKC z ceną, ilość 10.9",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Kolejka spółki pełna -- zlecenie na pewno nie weszło, można ponowić",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "504", description = "Silnik nie odpowiedział na czas -- stan nieznany, nie ponawiać w ciemno",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    OrderView place(@Parameter(example = "CDR") @PathVariable String symbol,
                    @Valid @RequestBody PlaceOrderRequest request) {
        // Najpierw zasob (404), potem tresc (400) -- jak w kazdym REST API.
        InstrumentEngine instrument = engine.instrument(symbol);
        long price = request.priceInGrosze();
        return OrderView.of(EngineCalls.await(
                instrument.place(request.side(), request.type(), price, request.quantity()), timeout));
    }

    @DeleteMapping("/{symbol}/orders/{orderId}")
    @Operation(summary = "Anuluj zlecenie czekające w arkuszu",
            description = "404, gdy zlecenia już nie ma: zrealizowane, anulowane albo nieznane.")
    EventView cancel(@Parameter(example = "CDR") @PathVariable String symbol, @PathVariable long orderId) {
        return EngineCalls.await(engine.instrument(symbol).cancel(orderId), timeout)
                .map(EventView::of)
                .orElseThrow(() -> new OrderNotFoundException(symbol, orderId));
    }

    @GetMapping("/{symbol}/book")
    @Operation(summary = "Arkusz spółki", description = "Najlepsze poziomy cen po obu stronach i numer ostatniego zdarzenia.")
    BookView book(@Parameter(example = "CDR") @PathVariable String symbol,
                  @Parameter(description = "Ile poziomów cen z każdej strony")
                  @RequestParam(defaultValue = "10") @Min(1) @Max(100) int levels) {
        return BookView.of(EngineCalls.await(engine.instrument(symbol).snapshot(levels), timeout));
    }
}
