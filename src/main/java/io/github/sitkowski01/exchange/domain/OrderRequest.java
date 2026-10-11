package io.github.sitkowski01.exchange.domain;

import java.util.Objects;

/**
 * Zlecenie wchodzace do arkusza.
 *
 * <p>Ceny sa w groszach (long), nigdy w double: 0.1 + 0.2 != 0.3 w arytmetyce
 * zmiennoprzecinkowej, a gielda nie moze sie mylic o grosz. Zamiana na zlotowki
 * dzieje sie dopiero na brzegu systemu (API).
 *
 * @param id       nadawany przez silnik, dodatni i scisle rosnacy -- wyznacza kolejnosc w czasie
 * @param price    cena graniczna w groszach; dla MARKET zawsze 0, najwyzej {@link #MAX_PRICE}
 * @param quantity liczba akcji, od 1 do {@link #MAX_QUANTITY}
 */
public record OrderRequest(long id, Side side, OrderType type, long price, long quantity) {

    /**
     * Gorna granica wielkosci zlecenia. Bez niej suma ilosci na poziomie cenowym
     * moglaby przekroczyc zakres long i po cichu zrobic sie ujemna.
     * Z nia trzeba by ponad 9 miliardow zlecen na jednej cenie.
     */
    public static final long MAX_QUANTITY = 1_000_000_000L;

    /**
     * Gorna granica ceny: 10 mln zl. Cena razy ilosc (obrot) miesci sie wtedy w long z zapasem,
     * a cena w groszach -- w liczbie calkowitej JavaScriptu (2^53) po stronie przegladarki.
     */
    public static final long MAX_PRICE = 1_000_000_000L;

    public OrderRequest {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(type, "type");
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive, was " + id);
        }
        if (quantity <= 0 || quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException("quantity must be between 1 and " + MAX_QUANTITY + ", was " + quantity);
        }
        if (type == OrderType.LIMIT && price <= 0) {
            throw new IllegalArgumentException("limit price must be positive, was " + price);
        }
        if (price > MAX_PRICE) {
            throw new IllegalArgumentException("price must be at most " + MAX_PRICE + ", was " + price);
        }
        if (type == OrderType.MARKET && price != 0) {
            throw new IllegalArgumentException("market order has no price, was " + price);
        }
    }

    public static OrderRequest limit(long id, Side side, long price, long quantity) {
        return new OrderRequest(id, side, OrderType.LIMIT, price, quantity);
    }

    public static OrderRequest market(long id, Side side, long quantity) {
        return new OrderRequest(id, side, OrderType.MARKET, 0, quantity);
    }
}
