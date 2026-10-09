package io.github.sitkowski01.exchange.marketdata;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Sesja WebSocket bez sieci: pamieta wyslane teksty i status zamkniecia, potrafi "zawisnac". */
final class FakeSession {

    final WebSocketSession session = mock(WebSocketSession.class);
    final List<String> sent = Collections.synchronizedList(new ArrayList<>());
    final AtomicReference<CloseStatus> closedWith = new AtomicReference<>();
    /** Dopoki zamkniety (count 1), kazde wyslanie czeka -- jak klient, ktory nie czyta. */
    volatile CountDownLatch gate = new CountDownLatch(0);

    FakeSession() throws Exception {
        when(session.getId()).thenReturn(UUID.randomUUID().toString());
        doAnswer(invocation -> {
            gate.await();
            sent.add(((TextMessage) invocation.getArgument(0)).getPayload());
            return null;
        }).when(session).sendMessage(any());
        doAnswer(invocation -> {
            closedWith.compareAndSet(null, invocation.getArgument(0));
            return null;
        }).when(session).close(any(CloseStatus.class));
    }

    static FakeSession create() {
        try {
            return new FakeSession();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
