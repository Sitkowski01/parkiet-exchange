package io.github.sitkowski01.exchange.bots;

import java.util.OptionalLong;

/** Skad boty biora cene startowa spolki. Brak ceny to pusty wynik, nigdy wyjatek. */
@FunctionalInterface
public interface ReferencePriceSource {

    /** @return ostatnia cena w groszach albo pusto, gdy zrodlo jej nie zna lub nie odpowiada */
    OptionalLong priceInGrosze(String symbol);
}
