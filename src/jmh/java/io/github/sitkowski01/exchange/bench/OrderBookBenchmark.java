package io.github.sitkowski01.exchange.bench;

import io.github.sitkowski01.exchange.domain.BookEvent;
import io.github.sitkowski01.exchange.domain.OrderBook;
import io.github.sitkowski01.exchange.domain.OrderRequest;
import io.github.sitkowski01.exchange.domain.Side;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Sam arkusz, bez watkow i kolejek: ile operacji na sekundzie wytrzymuje kojarzenie.
 *
 * <p>Mieszanka jak na zywym rynku: zlecenia LIMIT wokol ceny (czesc sie krzyzuje, czesc czeka),
 * co dziesiate MARKET, a po kazdym zleceniu anulowanie jednego ze starszych -- dzieki temu arkusz
 * ma staly rozmiar (kilkadziesiat zlecen), a nie rosnie przez caly pomiar.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class OrderBookBenchmark {

    private static final int ORDERS_PREPARED = 1 << 16;

    private OrderBook book;
    private Side[] sides;
    private long[] prices;
    private long[] quantities;
    private boolean[] market;
    private long nextId;

    @Setup
    public void setUp() {
        SplittableRandom random = new SplittableRandom(42);
        sides = new Side[ORDERS_PREPARED];
        prices = new long[ORDERS_PREPARED];
        quantities = new long[ORDERS_PREPARED];
        market = new boolean[ORDERS_PREPARED];
        for (int i = 0; i < ORDERS_PREPARED; i++) {
            sides[i] = random.nextBoolean() ? Side.BUY : Side.SELL;
            prices[i] = 10_000 + random.nextInt(-20, 21);
            quantities[i] = 1 + random.nextInt(100);
            market[i] = random.nextInt(10) == 0;
        }
        book = new OrderBook();
        nextId = 0;
    }

    /** Jedno zlecenie (losowane z przygotowanej puli -- losowanie nie liczy sie do pomiaru). */
    @Benchmark
    public List<BookEvent> submit() {
        long id = ++nextId;
        int i = (int) (id & (ORDERS_PREPARED - 1));
        OrderRequest order = market[i]
                ? OrderRequest.market(id, sides[i], quantities[i])
                : OrderRequest.limit(id, sides[i], prices[i], quantities[i]);
        List<BookEvent> events = book.submit(order);
        if (id > 50) {
            book.cancel(id - 50);
        }
        return events;
    }
}
