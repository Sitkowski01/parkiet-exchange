package io.github.sitkowski01.exchange.engine;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Identyfikator zlecenia nadany przez klienta gieldy (np. dom maklerski) -- odpowiednik ClOrdID z FIX.
 *
 * <p>Po nim gielda rozpoznaje powtorke: klient, ktory nie dostal odpowiedzi (504), wysyla zlecenie
 * jeszcze raz z tym samym identyfikatorem i dostaje to, co silnik zrobil za pierwszym razem,
 * zamiast drugiego zlecenia.
 */
public record ClientOrderId(String value) {

    public static final int MAX_LENGTH = 64;

    /** Litery, cyfry i kilka separatorow -- bez spacji i znakow, ktore trzeba by escapowac w logach czy URL-ach. */
    public static final String FORMAT = "[A-Za-z0-9._:-]{1," + MAX_LENGTH + "}";

    private static final Pattern PATTERN = Pattern.compile(FORMAT);

    public ClientOrderId {
        Objects.requireNonNull(value, "value");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("client order id must match " + FORMAT + ", was '" + value + "'");
        }
    }

    /** {@code null} z zadania HTTP albo wiersza dziennika to brak identyfikatora, nie blad. */
    public static ClientOrderId ofNullable(String value) {
        return value == null ? null : new ClientOrderId(value);
    }

    /** Odwrotnosc {@link #ofNullable}: do kolumny w bazie i pola w JSON-ie. */
    public static String valueOrNull(ClientOrderId id) {
        return id == null ? null : id.value();
    }

    @Override
    public String toString() {
        return value;
    }
}
