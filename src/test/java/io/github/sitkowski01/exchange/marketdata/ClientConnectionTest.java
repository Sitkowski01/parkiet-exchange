package io.github.sitkowski01.exchange.marketdata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@Timeout(10)
class ClientConnectionTest {

    private final FakeSession fake = FakeSession.create();

    @Test
    void deliversInOrder() {
        ClientConnection connection = new ClientConnection(fake.session, 100);
        for (int i = 1; i <= 50; i++) {
            connection.send("m" + i);
        }

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(fake.sent).hasSize(50));
        assertThat(fake.sent).startsWith("m1", "m2", "m3").endsWith("m50");
        connection.close(CloseStatus.NORMAL);
    }

    /**
     * Klient nie czyta: wysylajacy nie czeka ani chwili (ani na kolejke, ani na zamkniecie sesji),
     * a po przepelnieniu klient jest rozlaczany -- przez wlasny watek, nie przez wysylajacego.
     */
    @Test
    void slowClientIsDisconnectedWithoutBlockingSender() {
        fake.gate = new CountDownLatch(1);
        ClientConnection connection = new ClientConnection(fake.session, 3);

        long start = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            connection.send("m" + i);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));

        assertThat(connection.isOpen()).isFalse();
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(fake.closedWith.get()).isEqualTo(ClientConnection.TOO_SLOW));
    }

    @Test
    void failedWriteClosesWithServerError() throws IOException {
        doThrow(new IOException("broken pipe")).when(fake.session).sendMessage(any(TextMessage.class));
        ClientConnection connection = new ClientConnection(fake.session, 10);
        connection.send("m1");

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(fake.closedWith.get()).isEqualTo(CloseStatus.SERVER_ERROR));
        assertThat(connection.isOpen()).isFalse();
    }

    /** Watek klienta spi na pustej kolejce -- samo close() musi go obudzic, zeby zamknal sesje. */
    @Test
    void closeOfIdleConnectionClosesSession() {
        ClientConnection connection = new ClientConnection(fake.session, 10);
        connection.send("m1");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(fake.sent).hasSize(1));

        connection.close(CloseStatus.GOING_AWAY);

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(fake.closedWith.get()).isEqualTo(CloseStatus.GOING_AWAY));
    }

    @Test
    void firstCloseReasonWinsAndLateMessagesAreIgnored() {
        ClientConnection connection = new ClientConnection(fake.session, 10);
        connection.close(CloseStatus.NORMAL);
        connection.close(CloseStatus.SERVER_ERROR);
        connection.send("late");

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(fake.closedWith.get()).isEqualTo(CloseStatus.NORMAL));
        assertThat(fake.sent).isEmpty();
    }
}
