package io.github.sitkowski01.exchange.bench;

import io.github.sitkowski01.exchange.domain.OrderType;
import io.github.sitkowski01.exchange.domain.Side;
import io.github.sitkowski01.exchange.engine.EventSink;
import io.github.sitkowski01.exchange.engine.MatchingEngine;
import io.github.sitkowski01.exchange.engine.OrderResult;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Silnik z watkami i kolejkami: zlecenie z innego watku → kolejka → watek spolki → odpowiedz.
 *
 * <p>Roznica miedzy tym a {@link OrderBookBenchmark} to koszt przekazania polecenia miedzy watkami.
 * Benchmark z szescioma spolkami pokazuje, czy instrumenty naprawde skaluja sie niezaleznie.
 * Odbiorca zdarzen jest pusty -- mierzymy silnik, nie baze.
 */
@State(Scope.Benchmark)
public class EngineBenchmark {

    private static final List<String> SYMBOLS = List.of("CDR", "PKO", "PKN", "PZU", "KGH", "ALE");
    private static final int BATCH = 100;

    private MatchingEngine engine;

    @Setup(Level.Trial)
    public void start() {
        engine = new MatchingEngine(SYMBOLS, 100_000, Clock.systemUTC(), EventSink.NONE);
    }

    @TearDown(Level.Trial)
    public void stop() {
        engine.close();
    }

    /** Czas jednego zlecenia "tam i z powrotem" (wolajacy czeka na odpowiedz), jeden klient. */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public OrderResult roundTripOneClient() {
        return place("CDR");
    }

    /** Czterech klientow naraz na jedna spolke: wszyscy czekaja w kolejce do jednego watku. */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Threads(4)
    public OrderResult throughputOneSymbolFourClients() {
        return place("CDR");
    }

    /** Czterech klientow, kazdy na losowa z szesciu spolek: tu pracuje szesc watkow silnika. */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Threads(4)
    public OrderResult throughputSixSymbolsFourClients() {
        return place(SYMBOLS.get(ThreadLocalRandom.current().nextInt(SYMBOLS.size())));
    }

    /**
     * Klient, ktory nie czeka na kazda odpowiedz: wysyla 100 zlecen, potem czeka na ostatnie.
     * Tak mierzymy pojemnosc silnika, a nie opoznienie budzenia watku. Wynik w zleceniach na sekunde.
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @OperationsPerInvocation(BATCH)
    public OrderResult pipelinedOneSymbol() {
        return pipelined(() -> "CDR");
    }

    /** To samo na szesc spolek z szesciu watkow klientow -- tu widac, czy spolki skaluja sie niezaleznie. */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @OperationsPerInvocation(BATCH)
    @Threads(6)
    public OrderResult pipelinedSixSymbols(ClientSymbol client) {
        return pipelined(() -> client.symbol);
    }

    /** Kazdy watek klienta dostaje wlasna spolke. */
    @State(Scope.Thread)
    public static class ClientSymbol {
        private static final AtomicInteger NEXT = new AtomicInteger();
        final String symbol = SYMBOLS.get(Math.floorMod(NEXT.getAndIncrement(), SYMBOLS.size()));
    }

    private OrderResult pipelined(Supplier<String> symbol) {
        CompletableFuture<OrderResult> last = null;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < BATCH; i++) {
            Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
            last = engine.instrument(symbol.get())
                    .place(side, OrderType.LIMIT, 10_000 + random.nextInt(-5, 6), 1 + random.nextInt(10));
        }
        return last.join();
    }

    private OrderResult place(String symbol) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
        // Wasko wokol ceny i male ilosci: arkusz caly czas sie krzyzuje, wiec nie rosnie bez konca.
        return engine.instrument(symbol)
                .place(side, OrderType.LIMIT, 10_000 + random.nextInt(-5, 6), 1 + random.nextInt(10))
                .join();
    }
}
