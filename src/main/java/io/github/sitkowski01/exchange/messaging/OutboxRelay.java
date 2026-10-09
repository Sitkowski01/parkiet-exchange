package io.github.sitkowski01.exchange.messaging;

import io.github.sitkowski01.exchange.persistence.EventJournal;
import io.github.sitkowski01.exchange.persistence.EventJournal.StoredEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Transactional outbox: przenosi zdarzenia z dziennika do Kafki.
 *
 * <p>W jednej transakcji: zablokuj kursor, wez zdarzenia o id wiekszym niz kursor, wyslij je,
 * poczekaj na potwierdzenie brokera, przesun kursor. Gdy wysylka padnie, transakcja sie cofa
 * i kursor zostaje -- czesc wiadomosci mogla juz dojsc, stad dostawa co najmniej raz.
 *
 * <p>Kolejnosc: zdarzenia ida po id (kolejnosc zapisu), klucz wiadomosci to spolka (jedna
 * partycja), a producent Kafki jest idempotentny, wiec ponowienia nie zamieniaja kolejnosci.
 * Drugi relay ({@code skip locked}) nie czeka na zablokowany kursor, tylko odpuszcza ture.
 */
public final class OutboxRelay {

    private static final System.Logger LOG = System.getLogger(OutboxRelay.class.getName());

    private static final String LOCK_CURSOR =
            "select last_event_id from outbox_cursor where topic = ? for update skip locked";
    private static final String MOVE_CURSOR = "update outbox_cursor set last_event_id = ? where topic = ?";

    // Wlasny mapper, nie ten od REST API: ustawienia spring.jackson.* nie moga po cichu
    // zmienic formatu wiadomosci, na ktorym polegaja inne serwisy.
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EventJournal journal;
    private final MessagePublisher publisher;
    private final String topic;
    private final int batchSize;
    private final Duration sendTimeout;

    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, MessagePublisher publisher, String topic,
                       int batchSize, Duration sendTimeout) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.journal = new EventJournal(jdbc);
        this.publisher = publisher;
        this.topic = topic;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
    }

    /** Wysyla zaleglosci paczkami, az zostanie mniej niz jedna pelna paczka. Blad -- do nastepnej tury. */
    @Scheduled(fixedDelayString = "${exchange.outbox.poll-interval}")
    public void poll() {
        try {
            while (relayOnce() == batchSize) {
                // dalej, dopoki sa zaleglosci
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "outbox relay failed, will retry: " + e);
        }
    }

    /** @return ile zdarzen wyslano w tej paczce; 0 takze wtedy, gdy kursor trzyma inny relay */
    public int relayOnce() {
        Integer sent = tx.execute(status -> {
            List<Long> cursor = jdbc.queryForList(LOCK_CURSOR, Long.class, topic);
            if (cursor.isEmpty()) {
                return 0;
            }
            List<StoredEvent> batch = journal.readAfter(cursor.getFirst(), batchSize);
            if (batch.isEmpty()) {
                return 0;
            }
            send(batch);
            jdbc.update(MOVE_CURSOR, batch.getLast().id(), topic);
            return batch.size();
        });
        return sent == null ? 0 : sent;
    }

    /**
     * Limit czasu obejmuje cala paczke, takze samo wywolanie send (gdy brokera nie ma, potrafi
     * blokowac do max.block.ms). Po pierwszym bledzie nie wysylamy reszty -- i tak pojdzie ponownie.
     */
    private void send(List<StoredEvent> batch) {
        long deadline = System.nanoTime() + sendTimeout.toNanos();
        AtomicBoolean failed = new AtomicBoolean();
        List<CompletableFuture<?>> acks = new ArrayList<>(batch.size());
        for (StoredEvent stored : batch) {
            if (failed.get()) {
                break;
            }
            if (System.nanoTime() - deadline > 0) {
                throw new IllegalStateException("broker did not confirm within " + sendTimeout);
            }
            String value = JSON.writeValueAsString(EventMessage.of(stored.runId(), stored.event()));
            CompletableFuture<?> ack = publisher.send(stored.event().symbol(), value);
            ack.whenComplete((ok, error) -> {
                if (error != null) {
                    failed.set(true);
                }
            });
            acks.add(ack);
        }
        awaitAll(acks, deadline);
    }

    private void awaitAll(List<CompletableFuture<?>> acks, long deadline) {
        try {
            long left = Math.max(0, deadline - System.nanoTime());
            CompletableFuture.allOf(acks.toArray(CompletableFuture[]::new)).get(left, TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("broker rejected a message", e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("broker did not confirm within " + sendTimeout, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the broker", e);
        }
    }
}
