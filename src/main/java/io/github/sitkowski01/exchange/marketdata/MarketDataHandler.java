package io.github.sitkowski01.exchange.marketdata;

import io.github.sitkowski01.exchange.engine.BookSnapshot;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code ws://host/ws/market?symbols=CDR,PKO} -- strumien tylko do odczytu.
 *
 * <p>Kolejnosc przy podlaczeniu: najpierw subskrypcja, potem obraz arkusza. Odwrotnie transakcja
 * zawarta miedzy obrazem a subskrypcja przepadlaby bez sladu. Tak moze przyjsc transakcja
 * sprzed obrazu albo starszy obraz po nowszym -- klient rozpozna je po {@code sequence} i odrzuci.
 */
final class MarketDataHandler extends TextWebSocketHandler {

    static final CloseStatus BAD_SYMBOLS = CloseStatus.POLICY_VIOLATION.withReason("unknown or missing symbols");
    static final CloseStatus FULL = CloseStatus.SERVICE_OVERLOAD.withReason("too many clients, try later");

    private final MarketDataHub hub;
    private final BookBroadcaster books;
    private final Set<String> known;
    private final int clientQueue;
    private final int maxClients;
    private final Map<String, ClientConnection> connections = new ConcurrentHashMap<>();

    MarketDataHandler(MarketDataHub hub, BookBroadcaster books, Set<String> known, int clientQueue, int maxClients) {
        this.hub = hub;
        this.books = books;
        this.known = Set.copyOf(known);
        this.clientQueue = clientQueue;
        this.maxClients = maxClients;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        List<String> symbols = symbols(session);
        if (symbols.isEmpty() || !known.containsAll(symbols)) {
            session.close(BAD_SYMBOLS);
            return;
        }
        // Kazdy klient to watek, kolejka i obrazy arkusza z kolejki silnika -- bez limitu
        // skrypt otwierajacy polaczenia w petli odbieralby miejsce prawdziwym zleceniom.
        if (connections.size() >= maxClients) {
            session.close(FULL);
            return;
        }
        ClientConnection connection = new ClientConnection(session, clientQueue);
        connections.put(session.getId(), connection);
        symbols.forEach(symbol -> hub.subscribe(symbol, connection));
        Map<String, CompletableFuture<BookSnapshot>> snapshots = books.snapshots(new LinkedHashSet<>(symbols));
        for (CompletableFuture<BookSnapshot> snapshot : snapshots.values()) {
            connection.send(books.await(snapshot));
        }
        // Klient mogl sie rozlaczyc w trakcie -- wtedy afterConnectionClosed juz go wypisal,
        // a petla wyzej mogla go zapisac z powrotem.
        if (!connection.isOpen()) {
            hub.unsubscribe(connection);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ClientConnection connection = connections.remove(session.getId());
        if (connection != null) {
            hub.unsubscribe(connection);
            connection.close(status);
        }
    }

    int clients() {
        return connections.size();
    }

    private static List<String> symbols(WebSocketSession session) {
        if (session.getUri() == null) {
            return List.of();
        }
        String raw = UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams().getFirst("symbols");
        // getQueryParams() zwraca wartosci zakodowane: "CDR%2CPKO" albo "%20PKO" trzeba odkodowac.
        String param = raw == null ? null : UriUtils.decode(raw, StandardCharsets.UTF_8);
        if (param == null || param.isBlank()) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(
                Arrays.stream(param.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()));
    }
}
