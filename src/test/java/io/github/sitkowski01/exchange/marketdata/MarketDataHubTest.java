package io.github.sitkowski01.exchange.marketdata;

import io.github.sitkowski01.exchange.domain.BookEvent;
import io.github.sitkowski01.exchange.domain.OrderType;
import io.github.sitkowski01.exchange.domain.Side;
import io.github.sitkowski01.exchange.engine.EngineEvent;
import io.github.sitkowski01.exchange.engine.MatchingEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.socket.CloseStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Hub i rozsylanie obrazow arkusza na prawdziwym silniku, z sesjami bez sieci. */
@Timeout(10)
class MarketDataHubTest {

    private final MarketDataHub hub = new MarketDataHub(100);
    private final MatchingEngine engine = new MatchingEngine(List.of("CDR", "PKO"), 100, Clock.systemUTC(), hub);
    private final BookBroadcaster books = new BookBroadcaster(hub, engine, 10, Duration.ofSeconds(1));
    private final FakeSession cdr = FakeSession.create();
    private final FakeSession pko = FakeSession.create();
    private final ClientConnection cdrClient = new ClientConnection(cdr.session, 100);
    private final ClientConnection pkoClient = new ClientConnection(pko.session, 100);

    @AfterEach
    void tearDown() {
        engine.close();
        hub.close();
        books.close();
        cdrClient.close(CloseStatus.NORMAL);
        pkoClient.close(CloseStatus.NORMAL);
    }

    @Test
    void tradeGoesOnlyToSubscribersOfThatSymbol() {
        hub.subscribe("CDR", cdrClient);
        hub.subscribe("PKO", pkoClient);

        hub.publish(List.of(new EngineEvent("CDR", 7, Instant.parse("2026-10-09T10:00:00Z"),
                new BookEvent.Trade(1, 2, Side.BUY, 10125, 3))));

        await().untilAsserted(() -> assertThat(cdr.sent).containsExactly(
                "{\"type\":\"trade\",\"symbol\":\"CDR\",\"sequence\":7,\"timestamp\":\"2026-10-09T10:00:00Z\","
                        + "\"price\":\"101.25\",\"quantity\":3,\"takerSide\":\"BUY\"}"));
        assertThat(pko.sent).isEmpty();
    }

    @Test
    void everyEventMarksSymbolChangedButOnlyTradesAreStreamed() {
        hub.subscribe("CDR", cdrClient);
        hub.process(List.of(new EngineEvent("CDR", 1, Instant.EPOCH, new BookEvent.OrderRested(1, Side.BUY, 100, 1)),
                new EngineEvent("PKO", 1, Instant.EPOCH, new BookEvent.OrderRested(1, Side.SELL, 200, 1))));

        assertThat(hub.drainChanged()).containsExactlyInAnyOrder("CDR", "PKO");
        assertThat(hub.drainChanged()).isEmpty();
        assertThat(cdr.sent).isEmpty();
    }

    /** Notowania sa stratne: pelna kolejka nie zatrzymuje silnika, tylko zleca odswiezenie arkusza. */
    @Test
    void fullQueueDropsBatchAndMarksSymbolForResync() {
        MarketDataHub stopped = new MarketDataHub(1);
        stopped.close();
        stopped.publish(List.of(new EngineEvent("CDR", 1, Instant.EPOCH, new BookEvent.OrderRested(1, Side.BUY, 1, 1))));

        long start = System.nanoTime();
        stopped.publish(List.of(new EngineEvent("PKO", 1, Instant.EPOCH, new BookEvent.OrderRested(1, Side.BUY, 1, 1))));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(100));
        assertThat(stopped.droppedBatches()).isEqualTo(1);
        assertThat(stopped.drainChanged()).containsExactly("PKO");
    }

    @Test
    void changedBookIsSentToSubscribersOnly() {
        hub.subscribe("CDR", cdrClient);
        engine.instrument("CDR").place(Side.BUY, OrderType.LIMIT, 9990, 5).join();
        engine.instrument("PKO").place(Side.BUY, OrderType.LIMIT, 100, 1).join();
        hub.markChanged("CDR");
        hub.markChanged("PKO");

        books.broadcastChangedBooks();

        await().untilAsserted(() -> assertThat(cdr.sent).containsExactly("{\"type\":\"book\",\"symbol\":\"CDR\","
                + "\"sequence\":1,\"bids\":[{\"price\":\"99.90\",\"quantity\":5,\"orders\":1}],\"asks\":[]}"));
        assertThat(pko.sent).isEmpty();
    }

    @Test
    void failedSnapshotIsRetriedNextRound() {
        hub.subscribe("CDR", cdrClient);
        hub.markChanged("CDR");
        engine.close();

        books.broadcastChangedBooks();

        assertThat(hub.drainChanged()).containsExactly("CDR");
    }

    @Test
    void closedConnectionsFallOutWithoutUnsubscribe() {
        hub.subscribe("CDR", cdrClient);
        cdrClient.close(CloseStatus.GOING_AWAY);

        hub.broadcast("CDR", "x");

        assertThat(hub.hasSubscribers("CDR")).isFalse();
        assertThat(cdr.sent).isEmpty();
    }

    @Test
    void unsubscribedClientGetsNothing() {
        hub.subscribe("CDR", cdrClient);
        hub.unsubscribe(cdrClient);
        hub.broadcast("CDR", "x");

        assertThat(hub.hasSubscribers("CDR")).isFalse();
        assertThat(cdr.sent).isEmpty();
    }
}
