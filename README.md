# mini-exchange

[![CI](https://github.com/Sitkowski01/mini-exchange/actions/workflows/ci.yml/badge.svg)](https://github.com/Sitkowski01/mini-exchange/actions/workflows/ci.yml)

Silnik giełdy w **Javie 21** i **Spring Boot 4**: przyjmuje zlecenia kupna i sprzedaży,
kojarzy je w arkuszu według **ceny, a potem czasu** — tak jak GPW — i publikuje transakcje.

Cena na giełdzie nie przychodzi z zewnątrz. To cena ostatniej transakcji, czyli moment,
w którym czyjeś kupno spotkało się z czyjąś sprzedażą. Ten projekt jest źródłem notowań,
nie ich odbiorcą.

## Stan

| Etap | Zakres | |
|---|---|---|
| 1 | Szkielet, CI, narzędzia testowe, limit rozmiaru commita | ✅ |
| 2 | Arkusz zleceń: LIMIT, MARKET, anulowanie, głębokość rynku | ✅ |
| 3 | Silnik: jeden wątek na instrument, kolejka poleceń | ⏳ |
| 4 | REST API, PostgreSQL, Flyway, Testcontainers | |
| 5 | Transactional outbox → Kafka | |
| 6 | Notowania na żywo przez WebSocket | |
| 7 | Boty rynkowe zakotwiczone w cenach GPW (Stooq) | |
| 8 | Odtwarzanie arkusza z logu zdarzeń, metryki, benchmarki | |

## Arkusz zleceń

```
        KUPNO (bids)                 SPRZEDAŻ (asks)
   cena   ilość  zleceń          cena   ilość  zleceń
  100.00     50      2   ◄─┐  ┌─► 100.50     30      1
   99.50    120      4     │  │   101.00     80      3
   99.00     10      1     │  │   102.00    200      5
                     najlepsze ceny
                (spread = 100.50 − 100.00)
```

- **Priorytet ceny, potem czasu.** Najpierw wygrywa najwyższa oferta kupna i najniższa
  sprzedaży. Przy tej samej cenie — zlecenie, które przyszło wcześniej.
- **Transakcja po cenie zlecenia czekającego.** Kupujący gotów zapłacić 105 zł trafia na
  sprzedaż po 100 zł i płaci 100 zł.
- **LIMIT** — to, czego nie da się skojarzyć od razu, czeka w arkuszu.
  **MARKET** — bierze, co jest, a resztę anuluje; nigdy nie czeka.
- **Ceny w groszach jako `long`**, nigdy `double`. Zamiana na złotówki dopiero na brzegu systemu.

Kod: [`domain/OrderBook.java`](src/main/java/io/github/sitkowski01/exchange/domain/OrderBook.java).
Domena to czysta Java bez Springa — pilnuje tego test ArchUnit.

## Testy

| Rodzaj | Co sprawdza |
|---|---|
| Jednostkowe | każda reguła kojarzenia osobno: cena, czas, częściowe realizacje, anulowanie |
| **Różnicowe** | 30 000 losowych zleceń; arkusz musi dać **identyczne zdarzenia** jak celowo naiwna implementacja-wyrocznia |
| Niezmienniki | po każdym kroku: arkusz się nie krzyżuje, żadna akcja nie znika ani nie powstaje |
| Architektury | domena nie zależy od niczego poza JDK |
| **Mutacyjne (PIT)** | PIT psuje kod domeny i sprawdza, czy testy to zauważą. Próg: 85% |

```bash
./gradlew check     # testy + architektura + pokrycie (JaCoCo)
./gradlew pitest    # testy mutacyjne -> build/reports/pitest
```

## Konwencje

Commit ma maksymalnie **200 linii diffa** — da się go przeczytać w jednym podejściu.
Pilnuje tego hook; po sklonowaniu włącz go raz:

```bash
git config core.hooksPath .githooks
```
