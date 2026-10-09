package io.github.sitkowski01.exchange.marketdata;

import io.github.sitkowski01.exchange.engine.EventSink;
import io.github.sitkowski01.exchange.engine.MatchingEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.socket.CloseStatus;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

@Timeout(10)
class MarketDataHandlerTest {

    private final MarketDataHub hub = new MarketDataHub(100);
    private final MatchingEngine engine = new MatchingEngine(List.of("CDR", "PKO", "PZU"), 100, Clock.systemUTC(),
            EventSink.NONE);
    private final MarketDataHandler handler = new MarketDataHandler(hub,
            new BookBroadcaster(hub, engine, 10, Duration.ofSeconds(1)), Set.of("CDR", "PKO", "PZU"), 100, 1);
    private final FakeSession fake = FakeSession.create();

    @AfterEach
    void tearDown() {
        engine.close();
        hub.close();
    }

    @Test
    void subscribesEachSymbolOnceAndSendsItsBook() throws Exception {
        when(fake.session.getUri()).thenReturn(URI.create("ws://x/ws/market?symbols=CDR%2C%20PKO,,CDR"));

        handler.afterConnectionEstablished(fake.session);

        assertThat(hub.hasSubscribers("CDR")).isTrue();
        assertThat(hub.hasSubscribers("PKO")).isTrue();
        assertThat(hub.hasSubscribers("PZU")).isFalse();
        await().untilAsserted(() -> assertThat(fake.sent).hasSize(2));
        assertThat(fake.sent.get(0)).contains("\"type\":\"book\"", "\"symbol\":\"CDR\"");
        assertThat(fake.sent.get(1)).contains("\"symbol\":\"PKO\"");
        assertThat(fake.closedWith.get()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ws://x/ws/market", "ws://x/ws/market?symbols=", "ws://x/ws/market?symbols=%20,",
            "ws://x/ws/market?symbols=CDR,XYZ"})
    void missingOrUnknownSymbolsAreRejected(String uri) throws Exception {
        when(fake.session.getUri()).thenReturn(URI.create(uri));

        handler.afterConnectionEstablished(fake.session);

        assertThat(fake.closedWith.get()).isEqualTo(MarketDataHandler.BAD_SYMBOLS);
        assertThat(hub.hasSubscribers("CDR")).isFalse();
    }

    @Test
    void clientsOverLimitAreTurnedAway() throws Exception {
        when(fake.session.getUri()).thenReturn(URI.create("ws://x/ws/market?symbols=CDR"));
        handler.afterConnectionEstablished(fake.session);
        FakeSession second = FakeSession.create();
        when(second.session.getUri()).thenReturn(URI.create("ws://x/ws/market?symbols=PKO"));

        handler.afterConnectionEstablished(second.session);

        assertThat(second.closedWith.get()).isEqualTo(MarketDataHandler.FULL);
        assertThat(handler.clients()).isEqualTo(1);
        assertThat(hub.hasSubscribers("PKO")).isFalse();
    }

    @Test
    void missingUriIsRejected() throws Exception {
        handler.afterConnectionEstablished(fake.session);

        assertThat(fake.closedWith.get()).isEqualTo(MarketDataHandler.BAD_SYMBOLS);
    }

    @Test
    void closedClientIsUnsubscribedAndReleased() throws Exception {
        when(fake.session.getUri()).thenReturn(URI.create("ws://x/ws/market?symbols=CDR"));
        handler.afterConnectionEstablished(fake.session);

        handler.afterConnectionClosed(fake.session, CloseStatus.GOING_AWAY);

        assertThat(hub.hasSubscribers("CDR")).isFalse();
        assertThat(handler.clients()).isZero();
        await().untilAsserted(() -> assertThat(fake.closedWith.get()).isEqualTo(CloseStatus.GOING_AWAY));
        handler.afterConnectionClosed(fake.session, CloseStatus.NORMAL);
    }
}
