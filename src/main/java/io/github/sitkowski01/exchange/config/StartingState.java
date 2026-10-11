package io.github.sitkowski01.exchange.config;

import io.github.sitkowski01.exchange.engine.InstrumentState;

import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Od czego silnik zaczyna: od zera albo od przebiegu odtworzonego z dziennika.
 *
 * @param resumedRun  przebieg, ktory gielda ciagnie dalej; pusto = nowy przebieg
 * @param instruments stan spolek; spolki spoza mapy zaczynaja od pustego arkusza
 */
public record StartingState(OptionalLong resumedRun, Map<String, InstrumentState> instruments) {

    public StartingState {
        Objects.requireNonNull(resumedRun, "resumedRun");
        instruments = Map.copyOf(instruments);
    }

    public static StartingState fresh() {
        return new StartingState(OptionalLong.empty(), Map.of());
    }

    public static StartingState resume(long runId, Map<String, InstrumentState> instruments) {
        return new StartingState(OptionalLong.of(runId), instruments);
    }
}
