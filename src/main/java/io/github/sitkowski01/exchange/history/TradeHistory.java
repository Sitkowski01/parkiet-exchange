package io.github.sitkowski01.exchange.history;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Historia notowan liczona z dziennika zdarzen -- strona odczytu (CQRS). Silnik nie trzyma
 * historii i nie jest o nia pytany: wykres nie zabiera czasu watkowi, ktory kojarzy zlecenia,
 * a po restarcie historia jest ta sama, bo dziennik jest ten sam.
 */
public final class TradeHistory {

    /** Swieca OHLC: pierwsza, najwyzsza, najnizsza i ostatnia cena w przedziale (grosze) oraz wolumen. */
    public record Candle(Instant time, long open, long high, long low, long close, long volume) {
    }

    // date_bin tnie czas na rowne przedzialy (jak "zaokraglij w dol do 5 s"). Kolejnosc transakcji
    // w przedziale bierzemy z id dziennika, nie z czasu -- dwie transakcje moga miec ten sam znacznik.
    // Indeks engine_event_symbol_time (symbol, occurred_at) zaweza odczyt do ostatnich minut spolki.
    private static final String CANDLES = """
            select date_bin(make_interval(secs => ?), occurred_at, timestamptz 'epoch') as bucket,
                   (array_agg(price order by id))[1]      as open,
                   max(price)                             as high,
                   min(price)                             as low,
                   (array_agg(price order by id desc))[1] as close,
                   sum(quantity)                          as volume
            from engine_event
            where symbol = ? and type = 'TRADE' and occurred_at >= ?
            group by bucket
            order by bucket""";

    private final JdbcTemplate jdbc;

    public TradeHistory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Swiece z ostatnich {@code count} przedzialow do chwili {@code now}; przedzialy bez transakcji pomija. */
    public List<Candle> candles(String symbol, Duration interval, int count, Instant now) {
        long seconds = interval.toSeconds();
        if (seconds < 1) {
            throw new IllegalArgumentException("interval must be at least 1 s, was " + interval);
        }
        // Od poczatku najstarszego przedzialu (te same granice co date_bin od 1970), zeby pierwsza swieca
        // nie byla ucietym kawalkiem przedzialu.
        long currentBucket = Math.floorDiv(now.getEpochSecond(), seconds) * seconds;
        OffsetDateTime from = OffsetDateTime.ofInstant(Instant.ofEpochSecond(currentBucket - seconds * (count - 1)),
                ZoneOffset.UTC);
        return jdbc.query(CANDLES, (rs, n) -> new Candle(rs.getObject("bucket", OffsetDateTime.class).toInstant(),
                        rs.getLong("open"), rs.getLong("high"), rs.getLong("low"), rs.getLong("close"),
                        rs.getLong("volume")),
                seconds, symbol, from);
    }
}
