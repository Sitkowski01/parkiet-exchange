-- Migawka arkusza: stan spolki po zdarzeniu "sequence" -- zlecenia czekajace w kolejnosci priorytetu
-- i id ostatniego zlecenia. Start bierze najnowsza i dociaga tylko zdarzenia po niej, zamiast calego
-- przebiegu od pierwszego zdarzenia. Dziennik zostaje jedynym zrodlem prawdy: migawka to skrot,
-- ktory zawsze da sie z niego odtworzyc.
create table book_snapshot (
    run_id        bigint      not null references engine_run (id),
    symbol        varchar(10) not null,
    sequence      bigint      not null check (sequence > 0),
    last_order_id bigint      not null check (last_order_id >= 0),
    orders        jsonb       not null,
    taken_at      timestamptz not null default now(),
    primary key (run_id, symbol, sequence)
);

-- closed_at: ustawiane, gdy caly dziennik przebiegu jest juz zapisany. Puste przy kolejnym starcie =
-- przebieg sie urwal (awaria): klienci mogli dostac numery zlecen, ktorych dziennik nie zdazyl zapisac.
-- order_id_floor: od tego numeru (w gore) ida id zlecen po takiej awarii. W bazie, a nie tylko w pamieci:
-- inaczej kolejny czysty restart policzylby id znowu z dziennika i wydal stare numery jeszcze raz.
alter table engine_run add column closed_at timestamptz,
                       add column order_id_floor bigint not null default 0;
