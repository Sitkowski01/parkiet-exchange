package io.github.sitkowski01.exchange.bots;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Prawdziwe HTTP do lokalnego serwera udajacego Yahoo -- bez sieci i bez zaleznosci od ich humoru. */
@Timeout(10)
class YahooPriceSourceTest {

    private static final String CDR = """
            {"chart":{"result":[{"meta":{"currency":"PLN","symbol":"CDR.WA","regularMarketPrice":113.94}}],"error":null}}""";

    private HttpServer server;
    private volatile int status = 200;
    private volatile String body = CDR;
    private volatile long delayMillis;
    private volatile String lastPath;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void readsPriceInPlnAsGrosze() throws IOException {
        assertThat(source(Duration.ofSeconds(2)).priceInGrosze("CDR")).hasValue(11394);
        assertThat(lastPath).isEqualTo("/v8/finance/chart/CDR.WA");
    }

    @Test
    void notFoundGivesNoPrice() throws IOException {
        status = 404;
        body = "{\"chart\":{\"result\":null,\"error\":{\"code\":\"Not Found\"}}}";
        assertThat(source(Duration.ofSeconds(2)).priceInGrosze("XYZ")).isEmpty();
    }

    @Test
    void slowServerGivesNoPriceWithinTimeout() throws IOException {
        delayMillis = 3000;
        long start = System.nanoTime();

        assertThat(source(Duration.ofMillis(300)).priceInGrosze("CDR")).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void unreachableServerGivesNoPrice() {
        assertThat(new YahooPriceSource("http://127.0.0.1:1", Duration.ofMillis(500)).priceInGrosze("CDR")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"chart\":{\"result\":[{\"meta\":{\"currency\":\"USD\",\"regularMarketPrice\":10.5}}]}}",
            "{\"chart\":{\"result\":[{\"meta\":{\"currency\":\"PLN\"}}]}}",
            "{\"chart\":{\"result\":[{\"meta\":{\"currency\":\"PLN\",\"regularMarketPrice\":\"10.5\"}}]}}",
            "{\"chart\":{\"result\":[{\"meta\":{\"currency\":\"PLN\",\"regularMarketPrice\":0}}]}}",
            "{\"chart\":{\"result\":[]}}",
            "not json at all"
    })
    void anythingUnexpectedGivesNoPrice(String unexpected) throws IOException {
        body = unexpected;
        assertThat(source(Duration.ofSeconds(2)).priceInGrosze("CDR")).isEmpty();
    }

    @Test
    void roundsToGroszAndKeepsDecimalsExact() {
        assertThat(YahooPriceSource.parse(CDR.replace("113.94", "51.755"))).hasValue(5176);
        assertThat(YahooPriceSource.parse(CDR.replace("113.94", "347"))).isEqualTo(OptionalLong.of(34700));
    }

    private YahooPriceSource source(Duration timeout) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath = exchange.getRequestURI().getPath();
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return new YahooPriceSource("http://127.0.0.1:" + server.getAddress().getPort(), timeout);
    }
}
