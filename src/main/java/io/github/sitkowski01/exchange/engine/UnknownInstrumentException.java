package io.github.sitkowski01.exchange.engine;

public class UnknownInstrumentException extends RuntimeException {

    public UnknownInstrumentException(String symbol) {
        super("unknown instrument: " + symbol);
    }
}
