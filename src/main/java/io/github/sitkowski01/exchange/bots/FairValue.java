package io.github.sitkowski01.exchange.bots;

import java.util.random.RandomGenerator;

/**
 * "Prawdziwa" wartosc spolki w symulacji: losowe bladzenie w skali logarytmicznej.
 *
 * <p>Kazdy krok mnozy cene przez {@code e^(sigma * N(0,1))}, wiec zmiany sa procentowe (akcja za
 * 300 zl rusza sie o zlotowki, za 50 zl -- o grosze) i cena nigdy nie spada ponizej zera.
 * Market maker kwotuje wokol tej wartosci, a gracze losowi zbijaja jego kwotowania -- tak
 * transakcje ida za "prawda", choc nikt jej nie oglasza.
 */
final class FairValue {

    /** Krok ucinamy na 4 sigma -- bez tego jeden pech z ogona rozkladu zrobilby krach. */
    private static final double MAX_SIGMAS = 4.0;

    private final RandomGenerator random;
    private final double sigma;
    private double price;

    FairValue(long startGrosze, double sigma, RandomGenerator random) {
        if (startGrosze <= 0) {
            throw new IllegalArgumentException("start price must be positive, was " + startGrosze);
        }
        this.price = startGrosze;
        this.sigma = sigma;
        this.random = random;
    }

    /** Nastepny krok bladzenia, zaokraglony do grosza (najmniej 1 grosz). */
    long next() {
        double shock = Math.clamp(random.nextGaussian(), -MAX_SIGMAS, MAX_SIGMAS);
        price *= Math.exp(sigma * shock);
        price = Math.max(price, 1.0);
        return Math.round(price);
    }
}
