package io.github.sitkowski01.exchange.engine;

import io.github.sitkowski01.exchange.domain.BookEvent;
import io.github.sitkowski01.exchange.domain.BookEvent.OrderCancelled;
import io.github.sitkowski01.exchange.domain.BookEvent.OrderRested;
import io.github.sitkowski01.exchange.domain.BookEvent.Trade;
import io.github.sitkowski01.exchange.domain.OrderBook;
import io.github.sitkowski01.exchange.domain.OrderRequest;
import io.github.sitkowski01.exchange.domain.OrderType;
import io.github.sitkowski01.exchange.domain.Side;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static io.github.sitkowski01.exchange.domain.BookEvent.OrderCancelled.Reason.REQUESTED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Osiem watkow naraz zasypuje jeden instrument zleceniami i anulowaniami.
 *
 * <p>Potem caly przebieg jest odtwarzany jednowatkowo na swiezym arkuszu, w kolejnosci
 * z dziennika zdarzen. Wynik musi byc identyczny co do zdarzenia. Jesli watek silnika
 * kiedykolwiek przeplotlby dwa polecenia albo zgubil ktores, odtworzenie sie rozjedzie.
 */
class EngineConcurrencyTest {

    private static final int THREADS = 8;
    private static final int COMMANDS_PER_THREAD = 2_000;

    private record Placed(Side side, OrderType type, long price, long quantity) {
    }

    @RepeatedTest(3)
    // Wyscig na TreeMap potrafi zapetlic drzewo -- bez limitu zepsuty silnik wisialby w CI godzinami.
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void concurrentRunEqualsSequentialReplayOfItsJournal() throws Exception {
        List<List<EngineEvent>> journal = new ArrayList<>();   // pisany tylko z watku silnika
        Map<Long, Placed> placedById = new ConcurrentHashMap<>();
        List<List<CompletableFuture<?>>> futuresPerClient = new ArrayList<>();

        try (InstrumentEngine engine = InstrumentEngine.start("CDR", 1 << 16, Clock.systemUTC(), journal::add)) {
            CountDownLatch startGate = new CountDownLatch(1);
            List<Thread> clients = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                Random random = new Random(t);
                List<CompletableFuture<?>> own = new ArrayList<>();
                futuresPerClient.add(own);
                clients.add(Thread.ofPlatform().start(() -> {
                    await(startGate);
                    for (int i = 0; i < COMMANDS_PER_THREAD; i++) {
                        own.add(randomCommand(random, engine, placedById));
                    }
                }));
            }
            startGate.countDown();
            for (Thread client : clients) {
                client.join();
            }
            // join() watku klienta gwarantuje, ze jego lista jest tu w pelni widoczna.
            List<CompletableFuture<?>> all = futuresPerClient.stream().flatMap(List::stream).toList();
            CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).join();
        }
        // close() dolaczyl do watku silnika, wiec journal jest tu w pelni widoczny.

        long placedCount = placedById.size();
        assertThat(placedById.keySet()).containsExactlyInAnyOrderElementsOf(
                LongStream.rangeClosed(1, placedCount).boxed().toList());

        List<EngineEvent> flat = journal.stream().flatMap(List::stream).toList();
        assertThat(flat).extracting(EngineEvent::sequence)
                .containsExactlyElementsOf(LongStream.rangeClosed(1, flat.size()).boxed().toList());

        assertThat(replay(journal, placedById)).isEqualTo(journal.stream()
                .map(batch -> batch.stream().map(EngineEvent::event).toList())
                .toList());
    }

    private static CompletableFuture<?> randomCommand(Random random, InstrumentEngine engine, Map<Long, Placed> placed) {
        if (random.nextInt(5) == 0) {
            return engine.cancel(1 + random.nextInt(THREADS * COMMANDS_PER_THREAD));
        }
        Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
        boolean market = random.nextInt(10) == 0;
        Placed order = new Placed(side, market ? OrderType.MARKET : OrderType.LIMIT,
                market ? 0 : 95 + random.nextInt(11), 1 + random.nextInt(20));
        return engine.place(order.side(), order.type(), order.price(), order.quantity())
                .thenAccept(result -> placed.put(result.orderId(), order));
    }

    /** Odtwarza polecenia w kolejnosci z dziennika: kazda paczka to dokladnie jedno polecenie. */
    private static List<List<BookEvent>> replay(List<List<EngineEvent>> journal, Map<Long, Placed> placed) {
        OrderBook book = new OrderBook();
        Map<Long, Placed> byId = placed.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        long lastReplayedId = 0;
        List<List<BookEvent>> replayed = new ArrayList<>();
        for (List<EngineEvent> batch : journal) {
            BookEvent first = batch.getFirst().event();
            if (first instanceof OrderCancelled c && c.reason() == REQUESTED) {
                replayed.add(List.of(book.cancel(c.orderId()).orElseThrow()));
                continue;
            }
            long id = placingOrderId(first);
            // Zlecenia bez zdarzen nie istnieja: kazde zlozenie konczy sie co najmniej jednym.
            assertThat(id).isEqualTo(++lastReplayedId);
            Placed p = byId.get(id);
            replayed.add(book.submit(new OrderRequest(id, p.side(), p.type(), p.price(), p.quantity())));
        }
        return replayed;
    }

    private static long placingOrderId(BookEvent event) {
        return switch (event) {
            case Trade t -> t.takerOrderId();
            case OrderRested r -> r.orderId();
            case OrderCancelled c -> c.orderId();
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
