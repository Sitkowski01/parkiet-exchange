package io.github.sitkowski01.exchange.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.github.sitkowski01.exchange.domain.OrderType.LIMIT;
import static io.github.sitkowski01.exchange.domain.Side.BUY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Kolejka i zamykanie. Zeby deterministycznie zapelnic kolejke, pierwszy odbiorca zdarzen
 * zatrzymuje watek silnika, dopoki test go nie zwolni.
 */
@Timeout(30)
class EngineLifecycleTest {

    private final CountDownLatch engineBusy = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private InstrumentEngine engine;

    @AfterEach
    void cleanUp() {
        // Takze po nieudanej asercji: inaczej watek silnika przezylby test i blokowal JVM.
        release.countDown();
        engine.close();
    }

    private void startBlockedEngine(int capacity) throws InterruptedException {
        engine = InstrumentEngine.start("CDR", capacity, Clock.systemUTC(), batch -> {
            engineBusy.countDown();
            await(release);
        });
        engine.place(BUY, LIMIT, 100, 1);
        assertThat(engineBusy.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void rejectsWhenQueueIsFull() throws InterruptedException {
        startBlockedEngine(2);
        CompletableFuture<OrderResult> queued1 = engine.place(BUY, LIMIT, 100, 1);
        CompletableFuture<OrderResult> queued2 = engine.place(BUY, LIMIT, 100, 1);

        assertThatThrownBy(() -> engine.place(BUY, LIMIT, 100, 1).join())
                .hasCauseInstanceOf(EngineRejectedException.class)
                .hasMessageContaining("queue full");

        release.countDown();
        assertThat(queued1.join().orderId()).isEqualTo(2);
        assertThat(queued2.join().orderId()).isEqualTo(3);
    }

    @Test
    void closeFinishesQueuedCommandsThenRejectsNewOnes() throws Exception {
        startBlockedEngine(8);
        List<CompletableFuture<OrderResult>> queued = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            queued.add(engine.place(BUY, LIMIT, 100, 1));
        }

        Thread closer = Thread.ofPlatform().start(engine::close);
        awaitClosing();
        release.countDown();
        closer.join(5_000);

        assertThat(closer.isAlive()).isFalse();
        assertThat(queued).allSatisfy(f -> assertThat(f).isCompleted().isNotCompletedExceptionally());
        assertThatThrownBy(() -> engine.snapshot(1).join())
                .hasCauseInstanceOf(EngineRejectedException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void errorInSinkDoesNotKillEngine() {
        engine = InstrumentEngine.start("CDR", 4, Clock.systemUTC(), batch -> {
            throw new AssertionError("sink bug");
        });

        assertThat(engine.place(BUY, LIMIT, 100, 1).join().orderId()).isEqualTo(1);
        assertThat(engine.place(BUY, LIMIT, 100, 1).join().orderId()).isEqualTo(2);
    }

    @Test
    void foreignInterruptOfEngineThreadIsIgnored() {
        engine = InstrumentEngine.start("INTR", 4, Clock.systemUTC(), EventSink.NONE);
        engine.snapshot(1).join();

        Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals("engine-INTR"))
                .forEach(Thread::interrupt);

        assertThat(engine.place(BUY, LIMIT, 100, 1).join().orderId()).isEqualTo(1);
    }

    /** Czeka, az close() z innego watku faktycznie przestanie przyjmowac polecenia. */
    private void awaitClosing() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (engine.isAccepting()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("engine still accepts commands after close()");
            }
            Thread.sleep(1);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
