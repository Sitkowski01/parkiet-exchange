-- Identyfikator zlecenia nadany przez klienta (ClOrdID z FIX) na kazdym zdarzeniu, ktore zlecenie wywolalo.
-- Po restarcie silnik odbudowuje z niego pamiec powtorek: klient, ktory po timeoucie wysle to samo
-- zlecenie jeszcze raz, dostanie pierwotny wynik zamiast drugiego zlecenia -- takze po restarcie gieldy.
-- Pamiec powtorek jest dokladnie tak trwala jak samo zlecenie: po twardej awarii, ktora zgubila ostatnie
-- milisekundy dziennika, nie ma ani zlecenia (arkusz odtwarza sie bez niego), ani jego identyfikatora --
-- ponowienie sklada je wtedy od nowa, zgodnie ze stanem gieldy.
-- Tylko w dzienniku: publiczny strumien (Kafka) go nie niesie, bo transakcja pokazalaby identyfikator
-- drugiej strony innemu klientowi.
alter table engine_event add column client_order_id varchar(64)
    check (client_order_id ~ '^[A-Za-z0-9._:-]{1,64}$');

-- Pod odbudowe: ostatnie zlecenia z identyfikatorem danej spolki w przebiegu.
create index engine_event_client_order on engine_event (run_id, symbol, sequence) where client_order_id is not null;
