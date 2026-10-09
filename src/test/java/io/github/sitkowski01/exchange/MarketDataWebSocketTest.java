package io.github.sitkowski01.exchange;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Notowania przez prawdziwe gniazdo: klient WebSocket na losowym porcie, zlecenia przez HTTP.
 * Te same adnotacje co MiniExchangeApplicationTests, wiec oba testy dziela jeden kontekst (i kontenery).
 * PKN, bo inne testy w tym kontekscie handluja PZU i KGH.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, KafkaContainerConfiguration.class})
class MarketDataWebSocketTest {

    @LocalServerPort
    int port;

    @Autowired
    MockMvcTester mvc;

    @Test
    void clientGetsBookThenTradesThenUpdatedBook() throws Exception {
        Client client = connect("PKN");
        String first = client.next();
        assertThat(first).contains("\"type\":\"book\"", "\"symbol\":\"PKN\"");
        long seen = JsonPath.<Number>read(first, "$.sequence").longValue();

        place("{\"side\":\"SELL\",\"type\":\"LIMIT\",\"price\":\"64.50\",\"quantity\":10}");
        place("{\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":4}");

        // Transakcja przychodzi sama; obraz arkusza najpozniej po book-interval.
        String trade = client.nextOfType("trade");
        assertThat(trade).contains("\"price\":\"64.50\"", "\"quantity\":4", "\"takerSide\":\"BUY\"");
        assertThat(JsonPath.<Number>read(trade, "$.sequence").longValue()).isGreaterThan(seen);
        String book = client.nextBookAfter(JsonPath.<Number>read(trade, "$.sequence").longValue());
        assertThat(book).contains("{\"price\":\"64.50\",\"quantity\":6,\"orders\":1}");
        client.session.close();
    }

    @Test
    void unknownSymbolIsRejected() throws Exception {
        Client client = connect("XYZ");

        CloseStatus status = client.closed.get(5, TimeUnit.SECONDS);
        assertThat(status.getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
        assertThat(status.getReason()).isEqualTo("unknown or missing symbols");
    }

    private Client connect(String symbols) throws Exception {
        Client client = new Client();
        client.session = new StandardWebSocketClient()
                .execute(client, "ws://localhost:" + port + "/ws/market?symbols=" + symbols)
                .get(5, TimeUnit.SECONDS);
        return client;
    }

    private void place(String body) {
        assertThat(mvc.post().uri("/api/instruments/PKN/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .hasStatus(HttpStatus.CREATED);
    }

    private static final class Client extends TextWebSocketHandler {

        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        WebSocketSession session;

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed.complete(status);
        }

        String next() throws InterruptedException {
            String message = messages.poll(10, TimeUnit.SECONDS);
            assertThat(message).as("wiadomosc z serwera").isNotNull();
            return message;
        }

        String nextOfType(String type) throws InterruptedException {
            while (true) {
                String message = next();
                if (message.contains("\"type\":\"" + type + "\"")) {
                    return message;
                }
            }
        }

        String nextBookAfter(long sequence) throws InterruptedException {
            while (true) {
                String book = nextOfType("book");
                if (JsonPath.<Number>read(book, "$.sequence").longValue() >= sequence) {
                    return book;
                }
            }
        }
    }
}
