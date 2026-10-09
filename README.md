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
| 1 | Szkielet, CI, narzędzia testowe | ✅ |
| 2 | Arkusz zleceń: LIMIT, MARKET, anulowanie, głębokość rynku | ✅ |
| 3 | Silnik: jeden wątek na instrument, kolejka poleceń | ✅ |
| 4 | REST API, dziennik zdarzeń w PostgreSQL (Flyway, Testcontainers) | ✅ |
| 5 | Zapis poza wątkiem silnika, transactional outbox → Kafka | ✅ |
| 6 | Notowania na żywo przez WebSocket | ✅ |
| 7 | Boty rynkowe zakotwiczone w cenach GPW | ✅ |
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

## Silnik

```
 wątki HTTP ──┐
 wątki HTTP ──┼──► [ kolejka CDR ] ──► wątek engine-CDR ──► arkusz CDR ──► EventSink
 boty ────────┘     (ograniczona)       (jedyny, który        │
                                         go dotyka)           └──► odpowiedź (CompletableFuture)
              ───► [ kolejka PKO ] ──► wątek engine-PKO ──► arkusz PKO
```

- **Jeden wątek na instrument.** Arkusz dotyka tylko on, więc kojarzenie nie potrzebuje
  żadnych blokad, a kolejność jest jednoznaczna. Ten sam model stoi za LMAX Disruptor.
- **Id zleceń nadaje wątek silnika**, nie wołający — inaczej dwa równoległe żądania
  mogłyby wejść do kolejki w innej kolejności niż numeracja.
- **Ograniczona kolejka = backpressure.** Pełna kolejka odrzuca polecenie od razu,
  zamiast rosnąć w pamięci, aż serwer padnie.
- **Zdarzenia mają numer kolejny bez dziur** — odbiorca od razu widzi, że coś zgubił.
- **Zamykanie** kończy wszystkie przyjęte polecenia; jest odporne na przerwania wątku
  i odmawia wywołania z wątku silnika, które czekałoby na samo siebie.

## REST API

```bash
./gradlew bootRun     # potrzebny Docker: sam podnosi Postgresa i Kafkę z compose.yaml

curl localhost:8080/api/instruments
# ["CDR","PKO","PKN","PZU","KGH","ALE"]

curl -X POST localhost:8080/api/instruments/CDR/orders -H 'Content-Type: application/json' \
     -d '{"side":"SELL","type":"LIMIT","price":"250.40","quantity":10}'
# {"orderId":1,"events":[{"type":"RESTED","orderId":1,"side":"SELL","price":"250.40","quantity":10}]}

curl -X POST localhost:8080/api/instruments/CDR/orders -H 'Content-Type: application/json' \
     -d '{"side":"BUY","type":"MARKET","quantity":4}'
# {"orderId":2,"events":[{"type":"TRADE","makerOrderId":1,"takerOrderId":2,"takerSide":"BUY","price":"250.40","quantity":4}]}

curl localhost:8080/api/instruments/CDR/book?levels=5
curl -X DELETE localhost:8080/api/instruments/CDR/orders/1
```

| Kod | Kiedy |
|---|---|
| 201 / 200 | zlecenie przyjęte / anulowane / arkusz |
| 400 | zła treść: ułamek grosza, LIMIT bez ceny, MARKET z ceną, ilość 10.9, nieznany enum |
| 404 | nieznana spółka albo zlecenia nie ma w arkuszu |
| 503 | kolejka spółki pełna — zlecenie **na pewno nie weszło**, można ponowić |
| 504 | silnik nie odpowiedział na czas — stan **nieznany**, nie ponawiać w ciemno |

Błędy w formacie RFC 9457 (`application/problem+json`). Ceny w JSON-ie to tekst
(`"250.40"`), żeby klient w JS nie zgubił groszy w `double`. Spółki, pojemność kolejki
i limit czasu: [`application.yml`](src/main/resources/application.yml).

## Dziennik zdarzeń w PostgreSQL

Każde zdarzenie silnika trafia do tabeli `engine_event` — w kolejności, w jakiej powstało:

```
 run_id | symbol | sequence |  type     | side | price | quantity | reason
--------+--------+----------+-----------+------+-------+----------+-----------
      1 | PKO    |        1 | RESTED    | SELL |  6410 |      100 |
      1 | PKO    |        2 | TRADE     | BUY  |  6410 |       30 |
      1 | PKO    |        3 | CANCELLED |      |       |       70 | REQUESTED
```

- **Paczka zdarzeń jednego zlecenia w jednej transakcji** — nigdy pół zlecenia w bazie.
- **`run_id`** — każde uruchomienie giełdy numeruje zdarzenia od 1, więc restart nie zderza się z kluczem.
- **Schemat pilnuje kształtu wiersza** (`CHECK`): transakcja musi mieć makera i takera,
  anulowanie nie ma ceny. Migracje: Flyway ([`db/migration`](src/main/resources/db/migration)).
- **Zapis poza wątkiem silnika.** Silnik wrzuca zdarzenia do kolejki i wraca do kojarzenia;
  wątek `event-writer` zapisuje wszystko, co się nazbierało, jedną transakcją (group commit).
- **Baza leży → giełda staje, ale nic nie ginie.** Zapis jest ponawiany do skutku; gdy kolejka
  się zapełni, silnik czeka (backpressure). Insert jest idempotentny, więc ponowienie nie dubluje.

## Zdarzenia w Kafce (transactional outbox)

```
engine_event ──► OutboxRelay (co 100 ms) ──► Kafka: exchange.events, klucz = spółka
 (id > kursor)    1 transakcja: zablokuj kursor → wyślij → poczekaj na ack → przesuń kursor
```

Dziennik w bazie jest jedynym źródłem prawdy; do Kafki trafia z niego, nigdy z pominięciem.
Dzięki temu Kafka nie zobaczy zdarzenia, którego nie ma w bazie, a padnięty broker niczego nie gubi —
kursor stoi, dopóki broker nie potwierdzi.

- **Co najmniej raz.** Po awarii w połowie paczki część wiadomości pójdzie drugi raz.
  Odbiorca odrzuca powtórki po `(runId, symbol, sequence)`.
- **Kolejność w obrębie spółki.** Klucz = spółka → jedna partycja; producent idempotentny
  (`acks=all`), więc ponowienia nie zamieniają kolejności.
- **Dziennik zostaje append-only.** Relay pamięta tylko numer ostatniego wysłanego zdarzenia
  (`outbox_cursor`), zamiast oznaczać każdy wiersz.

Kontrakt wiadomości (JSON, ceny w groszach):

```json
{"runId":3,"symbol":"KGH","sequence":2,"timestamp":"2026-10-09T14:03:11.512Z","type":"TRADE",
 "makerOrderId":1,"takerOrderId":2,"takerSide":"BUY","price":18055,"quantity":5}
```

| `type` | pola |
|---|---|
| `RESTED` | `orderId`, `side`, `price`, `quantity` |
| `TRADE` | `makerOrderId`, `takerOrderId`, `takerSide`, `price`, `quantity` |
| `CANCELLED` | `orderId`, `quantity`, `reason` (`REQUESTED` / `NO_LIQUIDITY`) |

## Notowania na żywo (WebSocket)

```
ws://localhost:8080/ws/market?symbols=CDR,PKO
```

```json
{"type":"book","symbol":"CDR","sequence":41,"bids":[{"price":"250.30","quantity":12,"orders":2}],"asks":[...]}
{"type":"trade","symbol":"CDR","sequence":42,"timestamp":"2026-10-09T14:03:11.512Z","price":"250.40","quantity":4,"takerSide":"BUY"}
```

- Po podłączeniu klient dostaje **obraz arkusza** każdej spółki, potem **transakcje na bieżąco**
  i nowy obraz najwyżej co 100 ms, gdy arkusz się zmienił (conflation: przy 1000 zmian/s
  to 10 obrazów, zawsze najnowszy).
- **Zszywanie po `sequence`:** obraz uwzględnia wszystko do swojego numeru. Klient odrzuca
  transakcje o `sequence` ≤ ostatniego obrazu i obrazy starsze niż ostatni.
- **Notowania są stratne, dziennik nie.** Silnik nigdy nie czeka na notowania: gdy nie nadążają,
  paczka przepada, a spółka dostaje świeży obraz. Dziura w `sequence` transakcji = poczekaj na obraz.
- **Wolny klient nie spowalnia innych.** Każde połączenie ma własną kolejkę i wątek wirtualny;
  klient, który nie czyta, jest rozłączany (`1008 too slow`) i po ponownym połączeniu dostaje świeży obraz.

| Zamknięcie | Kiedy |
|---|---|
| `1008 unknown or missing symbols` | brak `symbols` albo nieznana spółka |
| `1008 too slow, reconnect` | kolejka klienta pełna |
| `1013 too many clients` | przekroczony limit połączeń |

## Boty i tryb demo

```bash
./gradlew bootRun --args='--spring.profiles.active=demo'
```

Giełda z ruchem: dla każdej spółki **animator rynku** trzyma kupno i sprzedaż wokół wartości
godziwej, a **gracz losowy** zbija jego kwotowania i dokłada zlecenia z limitem. Ceny startowe
to ostatnie kursy z GPW (Yahoo Finance, `CDR` → `CDR.WA`); gdy źródło nie odpowie — ceny
z konfiguracji.

```
             wartość godziwa (losowe błądzenie, kroki procentowe)
                         │
   animator: kupno ◄─────┼─────► sprzedaż      rozstęp 0,2%, przestawiane co 200 ms
                         │
   gracz losowy: 70% MARKET (transakcje), 30% LIMIT obok wartości (głębokość arkusza)
```

- Boty to zwykli klienci silnika — te same zlecenia co z HTTP, przez te same kolejki.
- Animator przestawia kwotowania **po jednej stronie naraz**, zaczynając od tej, od której cena
  ucieka: druga strona stoi cały czas, a bot nigdy nie handluje sam ze sobą.
- Każda spółka ma osobne zadanie — zablokowany silnik jednej nie zatrzymuje botów pozostałych.
- Boty ruszają dopiero po pełnym starcie aplikacji i stają jako pierwsze przy zamykaniu.
- Domyślnie wyłączone: bez profilu `demo` w arkuszu jest tylko to, co złożą klienci.

## Testy

| Rodzaj | Co sprawdza |
|---|---|
| Jednostkowe | każda reguła kojarzenia osobno: cena, czas, częściowe realizacje, anulowanie |
| **Różnicowe** | 30 000 losowych zleceń; arkusz musi dać **identyczne zdarzenia** jak celowo naiwna implementacja-wyrocznia |
| Niezmienniki | po każdym kroku: arkusz się nie krzyżuje, żadna akcja nie znika ani nie powstaje |
| **Współbieżności** | 8 wątków × 2000 poleceń naraz; potem cały przebieg odtworzony jednowątkowo z dziennika zdarzeń musi dać identyczny wynik |
| Zapisu asynchronicznego | kolejność, sklejanie paczek, ponawianie z rosnącą przerwą, backpressure, zamykanie z martwą bazą w limicie czasu |
| Cyklu życia | pełna kolejka, zamykanie w trakcie pracy, przerwania, błędy odbiorcy zdarzeń |
| HTTP | MockMvc na prawdziwym silniku: pełne odpowiedzi JSON, 400/404, zablokowany silnik → 504, pełna kolejka → 503 |
| **Integracyjne** | prawdziwy PostgreSQL i Kafka w Dockerze (Testcontainers): zapis i odczyt każdego typu zdarzenia, transakcja „wszystko albo nic”, `CHECK`-i schematu, cała droga HTTP → silnik → baza → Kafka |
| Outboxa | kolejność i format wiadomości, awaria brokera w połowie paczki, brak potwierdzeń, wolny broker w limicie czasu, drugi relay nie wysyła równolegle |
| Notowań | wolny klient rozłączany bez blokowania innych, pełna kolejka huba gubi paczkę zamiast zatrzymać silnik, obraz arkusza tylko dla spółek z widzami, limit klientów; plus prawdziwe gniazdo: obraz → transakcja → nowy obraz |
| Botów | źródło cen na lokalnym serwerze HTTP (timeout, 404, inna waluta), animator bez handlu z samym sobą przy skoku ceny, późne odpowiedzi silnika nie zostawiają zleceń w arkuszu, gracz z zaplanowanym losowaniem, setki ticków na prawdziwym silniku |
| Architektury | domena zależy tylko od JDK, silnik tylko od domeny i JDK, API nie zna bazy ani brokera |
| **Mutacyjne (PIT)** | PIT psuje kod domeny, silnika, API i notowań i sprawdza, czy testy to zauważą. Wynik: 228/243 (ocalałe to głównie mutanty równoważne przy zamykaniu wątków i logowanie), próg 85% |

```bash
./gradlew check     # testy + architektura + pokrycie (JaCoCo); Docker dla Testcontainers
./gradlew pitest    # testy mutacyjne -> build/reports/pitest
```
