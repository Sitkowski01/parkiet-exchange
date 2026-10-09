package io.github.sitkowski01.exchange.marketdata;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Jeden klient WebSocket: wlasna ograniczona kolejka i wlasny watek wirtualny, ktory pisze do gniazda.
 *
 * <p>Zapis do gniazda blokuje, gdy klient nie czyta (pelny bufor TCP) -- nawet na kilkanascie sekund.
 * Gdyby pisal hub, jeden zawieszony telefon zatrzymalby notowania wszystkim. Tu hub tylko wrzuca
 * do kolejki ({@link #send} nigdy nie czeka), a czeka wylacznie watek tego jednego klienta.
 *
 * <p>Zamkniecie tez robi watek klienta: {@link #close} tylko zapisuje powod i go budzi. Zamkniecie
 * sesji wysyla ramke i potrafi czekac jak zapis -- nie moze sie to dziac na watku huba. Przy okazji
 * sesji dotyka zawsze jeden watek, a WebSocketSession nie jest bezpieczna watkowo.
 */
final class ClientConnection {

    static final CloseStatus TOO_SLOW = CloseStatus.POLICY_VIOLATION.withReason("too slow, reconnect");

    private final WebSocketSession session;
    private final BlockingQueue<String> outbox;
    private final AtomicReference<CloseStatus> closing = new AtomicReference<>();
    private final Thread sender;

    ClientConnection(WebSocketSession session, int capacity) {
        this.session = session;
        this.outbox = new ArrayBlockingQueue<>(capacity);
        // Watek wirtualny: tysiac klientow to tysiac watkow, ale prawie za darmo -- czekajacy
        // watek wirtualny nie trzyma watku systemowego.
        this.sender = Thread.ofVirtual().name("ws-" + session.getId()).start(this::run);
    }

    /** Nie blokuje. Pelna kolejka = klient nie nadaza: rozlaczamy, zamiast zbierac wiadomosci w pamieci. */
    void send(String text) {
        if (!isOpen()) {
            return;
        }
        if (!outbox.offer(text)) {
            close(TOO_SLOW);
        }
    }

    boolean isOpen() {
        return closing.get() == null;
    }

    /** Nie blokuje: liczy sie pierwszy powod, sesje zamknie watek klienta. */
    void close(CloseStatus status) {
        if (closing.compareAndSet(null, status)) {
            sender.interrupt();
        }
    }

    private void run() {
        try {
            while (isOpen()) {
                session.sendMessage(new TextMessage(outbox.take()));
            }
        } catch (InterruptedException e) {
            // close() -- zamykamy ponizej
        } catch (IOException | RuntimeException e) {
            closing.compareAndSet(null, CloseStatus.SERVER_ERROR);
        }
        try {
            session.close(closing.get());
        } catch (IOException | RuntimeException ignored) {
            // juz rozlaczony
        }
    }
}
