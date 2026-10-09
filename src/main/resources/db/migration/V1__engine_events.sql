-- Kazde uruchomienie gieldy to osobny przebieg: silnik startuje z pustym arkuszem,
-- a numery zdarzen i id zlecen zaczynaja sie od 1. Bez run_id drugi start zderzylby
-- sie z kluczem glownym pierwszego.
create table engine_run (
    id         bigint generated always as identity primary key,
    started_at timestamptz not null default now()
);

-- Dziennik zdarzen arkusza, dokladnie w kolejnosci, w jakiej wyprodukowal je silnik.
-- Jedna tabela z kolumnami zamiast JSON-a: da sie po niej pytac zwyklym SQL-em
-- (np. wszystkie transakcje CDR z ostatniej godziny), a baza pilnuje ksztaltu wiersza.
create table engine_event (
    run_id         bigint      not null references engine_run (id),
    symbol         varchar(10) not null,
    sequence       bigint      not null,
    occurred_at    timestamptz not null,
    type           varchar(9)  not null,
    order_id       bigint,
    maker_order_id bigint,
    taker_order_id bigint,
    side           varchar(4),
    price          bigint, -- grosze
    quantity       bigint      not null check (quantity > 0),
    reason         varchar(12),
    primary key (run_id, symbol, sequence),
    check (side is null or side in ('BUY', 'SELL')),
    check (reason is null or reason in ('REQUESTED', 'NO_LIQUIDITY')),
    -- Kazdy typ ma swoj komplet kolumn; reszta musi byc pusta. Jawne "is not null", bo
    -- "price > 0" dla NULL daje NULL, a CHECK z wynikiem NULL... przepuszcza wiersz.
    check (
        (type = 'RESTED' and order_id is not null and side is not null and price is not null and price > 0
            and maker_order_id is null and taker_order_id is null and reason is null)
        or (type = 'TRADE' and maker_order_id is not null and taker_order_id is not null
            and side is not null and price is not null and price > 0 and order_id is null and reason is null)
        or (type = 'CANCELLED' and order_id is not null and reason is not null
            and side is null and price is null and maker_order_id is null and taker_order_id is null)
    )
);

comment on column engine_event.side is 'RESTED: strona zlecenia; TRADE: strona aktywnego (taker) - maker ma przeciwna';

-- Pod zapytania typu "transakcje CDR z ostatniej godziny" (i pozniej quote-stream).
create index engine_event_symbol_time on engine_event (symbol, occurred_at);
