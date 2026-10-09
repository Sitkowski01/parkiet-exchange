package io.github.sitkowski01.exchange.bots;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.OptionalLong;

/**
 * Ostatnia cena spolki z GPW z Yahoo Finance ({@code CDR} → {@code CDR.WA}).
 *
 * <p>To nieoficjalne API, wiec moze sie zmienic albo zniknac -- tak stalo sie ze Stooq, ktory
 * zablokowal pobieranie weryfikacja w JavaScripcie. Dlatego kazdy problem (timeout, 404, inny
 * format, inna waluta) to po prostu brak ceny, a boty startuja wtedy od ceny z konfiguracji.
 */
public final class YahooPriceSource implements ReferencePriceSource, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(YahooPriceSource.class.getName());
    // Liczby z ulamkiem prosto do BigDecimal: 113.94 jako double to 113.93999...
    private static final JsonMapper JSON =
            JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    private final HttpClient http;
    private final String baseUrl;
    private final Duration timeout;

    /** @param baseUrl np. {@code https://query1.finance.yahoo.com} -- w testach lokalny serwer */
    public YahooPriceSource(String baseUrl, Duration timeout) {
        this.baseUrl = baseUrl;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public OptionalLong priceInGrosze(String symbol) {
        URI uri = URI.create(baseUrl + "/v8/finance/chart/" + symbol + ".WA?range=1d&interval=1d");
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri).timeout(timeout)
                    // Bez naglowka przegladarki Yahoo potrafi odpowiedziec 429.
                    .header("User-Agent", "Mozilla/5.0 mini-exchange").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.log(System.Logger.Level.WARNING, symbol + ": price source answered " + response.statusCode());
                return OptionalLong.empty();
            }
            return parse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OptionalLong.empty();
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, symbol + ": price source failed: " + e);
            return OptionalLong.empty();
        }
    }

    /** Wyciaga {@code regularMarketPrice} w PLN i zamienia na grosze. Cokolwiek innego -- pusto. */
    static OptionalLong parse(String body) {
        JsonNode meta = JSON.readTree(body).path("chart").path("result").path(0).path("meta");
        JsonNode price = meta.path("regularMarketPrice");
        if (!"PLN".equals(meta.path("currency").asString(null)) || !price.isNumber()) {
            return OptionalLong.empty();
        }
        long grosze = price.decimalValue().setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
        return grosze > 0 ? OptionalLong.of(grosze) : OptionalLong.empty();
    }

    /** Klient HTTP trzyma watek selektora -- po pobraniu cen startowych nie jest juz potrzebny. */
    @Override
    public void close() {
        http.close();
    }
}
