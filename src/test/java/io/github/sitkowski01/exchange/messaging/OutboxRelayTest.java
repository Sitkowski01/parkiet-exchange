package io.github.sitkowski01.exchange.messaging;

import io.github.sitkowski01.exchange.TestcontainersConfiguration;
import io.github.sitkowski01.exchange.domain.BookEvent;
import io.github.sitkowski01.exchange.domain.BookEvent.OrderCancelled;
import io.github.sitkowski01.exchange.domain.Side;
import io.github.sitkowski01.exchange.engine.EngineEvent;
import io.github.sitkowski01.exchange.persistence.JdbcEventSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Relay na prawdziwym PostgreSQL, z atrapa brokera zamiast Kafki -- awarie wywolujemy na zyczenie. */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxRelayTest {

    private static final String TOPIC = "exchange.events";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    private final List<String> sent = Collections.synchronizedList(new ArrayList<>());
    private final MessagePublisher broker = (key, value) -> {
        sent.add(key + " " + value);
        return CompletableFuture.completedFuture(null);
    };
    private JdbcEventSink journal;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate engine_event, engine_run");
        jdbc.update("update outbox_cursor set last_event_id = 0");
        journal = JdbcEventSink.startRun(jdbc, tx);
    }

    @Test
    void sendsInWriteOrderAndMovesCursor() {
        journal.publish(List.of(
                event("PKO", 1, new BookEvent.OrderRested(1, Side.SELL, 6410, 100)),
                event("PKO", 2, new BookEvent.Trade(1, 2, Side.BUY, 6410, 30)),
                event("CDR", 1, new OrderCancelled(1, 5, OrderCancelled.Reason.NO_LIQUIDITY))));

        assertThat(relay(broker, 500).relayOnce()).isEqualTo(3);

        String head = "{\"runId\":" + journal.runId() + ",\"symbol\":";
        String time = ",\"timestamp\":\"2026-10-09T10:00:00Z\"";
        assertThat(sent).containsExactly(
                "PKO " + head + "\"PKO\",\"sequence\":1" + time
                        + ",\"type\":\"RESTED\",\"orderId\":1,\"side\":\"SELL\",\"price\":6410,\"quantity\":100}",
                "PKO " + head + "\"PKO\",\"sequence\":2" + time + ",\"type\":\"TRADE\",\"makerOrderId\":1,"
                        + "\"takerOrderId\":2,\"takerSide\":\"BUY\",\"price\":6410,\"quantity\":30}",
                "CDR " + head + "\"CDR\",\"sequence\":1" + time
                        + ",\"type\":\"CANCELLED\",\"orderId\":1,\"quantity\":5,\"reason\":\"NO_LIQUIDITY\"}");
        assertThat(cursor()).isEqualTo(maxId());
        assertThat(relay(broker, 500).relayOnce()).isZero();
    }

    @Test
    void brokerFailureLeavesCursorAndStopsSendingRest() {
        journal.publish(List.of(rested(1), rested(2), rested(3), rested(4), rested(5)));
        MessagePublisher failsOnSecond = (key, value) -> value.contains("\"sequence\":2")
                ? CompletableFuture.failedFuture(new IllegalStateException("broker down"))
                : broker.send(key, value);

        assertThatThrownBy(() -> relay(failsOnSecond, 500).relayOnce()).hasMessageContaining("broker rejected");
        assertThat(cursor()).isZero();
        assertThat(sent).as("po bledzie reszta paczki nie idzie").hasSize(1);

        sent.clear();
        relay(broker, 500).relayOnce();
        assertThat(sent).hasSize(5);
        assertThat(cursor()).isEqualTo(maxId());
    }

    @Test
    void brokerSilenceTimesOutWithoutMovingCursor() {
        journal.publish(List.of(rested(1)));
        OutboxRelay relay = new OutboxRelay(jdbc, tx, (key, value) -> new CompletableFuture<>(), TOPIC, 500,
                Duration.ofMillis(100));

        assertThatThrownBy(relay::relayOnce).hasMessageContaining("did not confirm within PT0.1S");
        assertThat(cursor()).isZero();
    }

    /** Scenariusz z review: bez brokera kazde send() blokuje do max.block.ms, a paczka ma 500 wiadomosci. */
    @Test
    void slowSendCallsAreBoundedBySendTimeout() {
        journal.publish(List.of(rested(1), rested(2), rested(3), rested(4), rested(5), rested(6)));
        MessagePublisher blocking = (key, value) -> {
            sleep(60);
            return broker.send(key, value);
        };
        OutboxRelay relay = new OutboxRelay(jdbc, tx, blocking, TOPIC, 500, Duration.ofMillis(100));

        assertThatThrownBy(relay::relayOnce).hasMessageContaining("did not confirm");
        assertThat(sent).hasSizeLessThan(4);
        assertThat(cursor()).isZero();
    }

    @Test
    void pollDrainsBacklogInBatches() {
        journal.publish(List.of(rested(1), rested(2), rested(3), rested(4), rested(5)));
        OutboxRelay relay = relay(broker, 2);

        assertThat(relay.relayOnce()).isEqualTo(2);
        relay.poll();

        assertThat(sent).hasSize(5);
        assertThat(cursor()).isEqualTo(maxId());
    }

    @Test
    void pollSwallowsFailureSoSchedulerKeepsRunning() {
        journal.publish(List.of(rested(1)));
        relay((key, value) -> CompletableFuture.failedFuture(new IllegalStateException("down")), 500).poll();

        assertThat(cursor()).isZero();
    }

    /** Drugi relay nie czeka na zablokowany kursor (skip locked) -- odpuszcza ture. */
    @Test
    void secondRelaySkipsWhileCursorIsLocked() throws Exception {
        journal.publish(List.of(rested(1)));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().start(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForList("select * from outbox_cursor for update");
            locked.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(relay(broker, 500).relayOnce()).isZero();
        assertThat(sent).isEmpty();

        release.countDown();
        holder.join();
        assertThat(relay(broker, 500).relayOnce()).isEqualTo(1);
    }

    private OutboxRelay relay(MessagePublisher publisher, int batchSize) {
        return new OutboxRelay(jdbc, tx, publisher, TOPIC, batchSize, Duration.ofSeconds(5));
    }

    private long cursor() {
        return jdbc.queryForObject("select last_event_id from outbox_cursor where topic = ?", Long.class, TOPIC);
    }

    private long maxId() {
        return jdbc.queryForObject("select max(id) from engine_event", Long.class);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static EngineEvent rested(long sequence) {
        return event("CDR", sequence, new BookEvent.OrderRested(sequence, Side.BUY, 100, 1));
    }

    private static EngineEvent event(String symbol, long sequence, BookEvent event) {
        return new EngineEvent(symbol, sequence, Instant.parse("2026-10-09T10:00:00Z"), event);
    }
}
