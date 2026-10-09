package io.github.sitkowski01.exchange.bots;

import io.github.sitkowski01.exchange.engine.InstrumentEngine;
import io.github.sitkowski01.exchange.engine.MatchingEngine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Ozywia gielde: co tick dla kazdej spolki przesuwa wartosc godziwa, animator przestawia
 * kwotowania, a gracz losowy z pewnym prawdopodobienstwem sklada zlecenie.
 *
 * <p>Boty to zwykli klienci silnika -- skladaja zlecenia tym samym API co HTTP i czekaja na
 * odpowiedz. Kazda spolka ma osobne zadanie i wlasny generator losowy: gdy silnik jednej
 * spolki stoi, boty pozostalych dzialaja dalej.
 */
public final class BotRunner implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(BotRunner.class.getName());

    /** Wszystko, czego boty potrzebuja z konfiguracji, w jednym miejscu. */
    public record BotSettings(double volatility, int spreadBps, long makerQuantity, double noiseProbability,
                              Duration timeout) {
    }

    private record SymbolBots(String symbol, RandomGenerator random, FairValue fair, MarketMaker maker,
                              NoiseTrader noise) {
    }

    private final List<SymbolBots> bots = new ArrayList<>();
    private final double noiseProbability;
    private ScheduledExecutorService timer;

    /**
     * @param startingPrices cena startowa w groszach; spolki spoza mapy nie dostaja botow
     * @param randoms        nowy generator dla kazdej spolki (generatory nie sa bezpieczne watkowo)
     */
    public BotRunner(MatchingEngine engine, Map<String, Long> startingPrices, BotSettings settings,
                     Supplier<RandomGenerator> randoms) {
        this.noiseProbability = settings.noiseProbability();
        startingPrices.forEach((symbol, price) -> {
            InstrumentEngine instrument = engine.instrument(symbol);
            RandomGenerator random = randoms.get();
            bots.add(new SymbolBots(symbol, random, new FairValue(price, settings.volatility(), random),
                    new MarketMaker(instrument, settings.spreadBps(), settings.makerQuantity(), settings.timeout()),
                    new NoiseTrader(instrument, random, settings.timeout())));
        });
    }

    public synchronized void start(Duration interval) {
        if (timer != null) {
            return;
        }
        timer = Executors.newScheduledThreadPool(Math.max(1, bots.size()),
                Thread.ofPlatform().name("bots-", 0).factory());
        for (SymbolBots b : bots) {
            timer.scheduleWithFixedDelay(() -> step(b), 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    public synchronized boolean isRunning() {
        return timer != null && !timer.isShutdown();
    }

    @Override
    public synchronized void close() {
        if (timer == null) {
            return;
        }
        timer.shutdownNow();
        try {
            timer.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Jeden krok dla wszystkich spolek po kolei -- do testow, bez watkow. */
    void tick() {
        bots.forEach(this::step);
    }

    List<String> symbols() {
        return bots.stream().map(SymbolBots::symbol).toList();
    }

    /** Nic stad nie wylatuje -- wyjatek z zadania okresowego po cichu zatrzymalby boty tej spolki. */
    private void step(SymbolBots b) {
        try {
            long fair = b.fair().next();
            b.maker().requote(fair);
            if (b.random().nextDouble() < noiseProbability) {
                b.noise().act(fair);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, b.symbol() + ": bot step failed: " + e);
        }
    }
}
