package io.github.sitkowski01.exchange.api;

import java.math.BigDecimal;

/**
 * Zamiana zlotowki ↔ grosze na brzegu systemu.
 *
 * <p>Na zewnatrz ceny sa w zlotowkach ({@code "101.25"}), bo tak je czyta czlowiek.
 * W srodku -- w groszach jako {@code long}. {@link BigDecimal}, a nie {@code double},
 * bo {@code 101.25} w double to w rzeczywistosci 101.2499999...
 */
final class Prices {

    private Prices() {
    }

    /** @throws InvalidOrderException gdy cena ma ulamek grosza albo nie miesci sie w long */
    static long toGrosze(BigDecimal zloty) {
        if (zloty.stripTrailingZeros().scale() > 2) {
            throw new InvalidOrderException("price must have at most 2 decimal places, was " + zloty);
        }
        try {
            return zloty.movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            throw new InvalidOrderException("price out of range: " + zloty, e);
        }
    }

    static BigDecimal toZloty(long grosze) {
        return BigDecimal.valueOf(grosze, 2);
    }
}
