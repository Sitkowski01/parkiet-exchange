package io.github.sitkowski01.exchange.api;

import io.github.sitkowski01.exchange.engine.EngineRejectedException;
import io.github.sitkowski01.exchange.engine.UnknownInstrumentException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Wyjatki → kody HTTP w formacie RFC 9457 (Problem Details). Klasa bazowa obsluguje
 * bledy samego Springa: zly JSON, nieznana wartosc enuma, nieudana walidacja → 400.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(InvalidOrderException.class)
    ProblemDetail invalid(InvalidOrderException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler({UnknownInstrumentException.class, OrderNotFoundException.class})
    ProblemDetail notFound(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** Pelna kolejka albo zamykany silnik: zlecenie na pewno nie weszlo, mozna ponowic. */
    @ExceptionHandler(EngineRejectedException.class)
    ProblemDetail rejected(EngineRejectedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    /**
     * Stan nieznany: ponowienie w ciemno mogloby zlozyc zlecenie dwa razy. Pelne rozwiazanie
     * to id zlecenia nadawane przez klienta (idempotencja) -- tu przynajmniej mowimy prawde.
     */
    @ExceptionHandler(EngineTimeoutException.class)
    ProblemDetail timeout(EngineTimeoutException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.GATEWAY_TIMEOUT,
                e.getMessage() + "; the command may still be executed, do not retry blindly");
    }
}
